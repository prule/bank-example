## Context

`H2ServerInitializer` already exists (shipped with F00 alongside the dev profile's Swagger UI exposure). It is a `@Component @Profile("dev") @ConditionalOnProperty("bank-core.h2.tcp-server.enabled", "true")` bean that wraps `org.h2.tools.Server.createTcpServer("-tcp", "-tcpAllowOthers", "-tcpPort", port)` and starts/stops it via `@PostConstruct` / `@PreDestroy`. The dev profile's `application-dev.yaml` already declares the property block — but with `enabled: false`. So today, a developer wanting to expose the TCP server has to either edit the YAML or pass `--bank-core.h2.tcp-server.enabled=true` on the command line. This change makes the dev-profile default `true` so the conveniently-typed command produces a conveniently-usable setup.

H2 in-memory databases live inside the JVM and are *shared across all connections inside that JVM by URL*. The application uses `jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`; the TCP server (running inside the same JVM) exposes the very same `bankcore` instance via `jdbc:h2:tcp://localhost:9092/mem:bankcore`. No data copy, no synchronisation. The TCP server is a window into the live application database.

Stakeholders:

- The developer running `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'` who wants to inspect tables with their DB client of choice.
- The operator who must be confident the TCP port is never opened in production. The `@Profile("dev")` guard provides that confidence — the bean is not constructed under the default profile regardless of property values.

Constraints inherited:

- The `dev` profile is the only place this hook lives. `default` and `test` profiles MUST NOT start the TCP server.
- F00 conventions: configuration knobs live in `application*.yaml`, not in Java constants. The port stays externalised under `bank-core.h2.tcp-server.port`.
- The TCP server uses the well-known H2 port `9092` by convention. No reason to deviate.

## Goals / Non-Goals

**Goals:**

- A developer running the dev profile with no extra flags gets a working H2 TCP server they can connect to from any standard JDBC client.
- The startup log line tells the developer the exact connection string so they don't have to find documentation.
- Production safety: zero exposure of the TCP server outside the `dev` profile.
- A regression test pins the behaviour in both directions: bean constructed under `dev`, bean absent otherwise.

**Non-Goals:**

- Switching the application's primary JDBC URL from in-memory to file-backed. That's a separate trade-off (persistence vs ephemerality) the developer can take per-session via `--spring.datasource.url=...`. The YAML comment will document the substitution recipe.
- Exposing the TCP server in any other profile. `default`/`test` stay off.
- Restricting connections to localhost only (i.e. removing `-tcpAllowOthers`). Developer laptops behind firewalls don't need the restriction; the existing behaviour is unchanged.
- TLS/authentication on the TCP port. H2's TCP server supports `-tcpPassword` for shutdown auth but not per-connection auth beyond the database user. For a dev-laptop use case the existing `sa`-with-empty-password is the same as `/h2-console` already exposes.

## Decisions

### Decision 1: Flip the dev-profile default, do NOT add the property to the default profile

`application-dev.yaml` toggles `bank-core.h2.tcp-server.enabled` from `false` to `true`. `application.yaml` (default profile) is NOT touched — the property stays absent there.

**Why:**

- Adding the property to `application.yaml` would imply "this is a supported production knob you can flip". It isn't — the bean is `@Profile("dev")`-gated and won't be constructed regardless. Leaving the property absent from the default profile signals "this is dev-only" through the YAML structure itself.
- The `@ConditionalOnProperty` defaults to `havingValue = "true"` matching only if the property is *present and equal to true*; an absent property is interpreted as `false`. So the default profile naturally falls through to "off" without any explicit declaration.

**Alternatives considered:**

- Set `enabled: true` in `application.yaml` and rely on `@Profile("dev")` alone to keep production safe. Rejected — defence-in-depth is cheap here; the YAML structure should signal intent.
- Remove the property from `application-dev.yaml` and let the `@ConditionalOnProperty(matchIfMissing = true)` annotation control it. Rejected — that would mean the property would have to be added to `@ConditionalOnProperty`, changing the bean's contract; the YAML-driven default is clearer.

### Decision 2: Log the connection string at startup, not just the port

The `@PostConstruct` log message changes from `H2 TCP server started on port {}` to `H2 TCP server started on port {} (connect with: jdbc:h2:tcp://localhost:{}/mem:bankcore, user sa, no password)`.

**Why:**

- A developer scanning startup output for "H2" should be able to copy-paste the connection string into DBeaver without context-switching to the docs.
- Including the `mem:bankcore` sub-URL is essential — H2's TCP URL syntax requires the developer to name the in-memory DB *inside* the TCP target, and the default in-mem name is taken from the application's `spring.datasource.url`. Documenting it inline avoids the "I see the TCP port but I don't know what DB to connect to" failure mode.

**Trade-off:** the log line gets longer (≈110 chars). For a dev-profile-only log emitted exactly once at startup, the readability win is worth the width cost.

### Decision 3: Test uses a randomised port via `@DynamicPropertySource`

The new `H2ServerInitializerDevProfileTest` does NOT bind to `9092` — that would race a developer's local `bootRun`. Instead it picks an OS-assigned free port at test start via `ServerSocket(0).getLocalPort()` and threads that port through `@DynamicPropertySource` so the `@Value("${bank-core.h2.tcp-server.port:9092}")` reads the test-only value.

**Why:**

- A test that binds to a fixed well-known port is hostile to developer workflows (`./gradlew test` while `bootRun` is running fails with `BindException`).
- Spring's `@DynamicPropertySource` runs before the application context starts, so the bean sees the test-chosen port when `@Value` resolves.
- The test asserts the *wiring* (bean exists, TCP connects, queries succeed) not the specific port. The dev YAML's port `9092` is verified by the YAML itself plus the `@ConditionalOnProperty` annotation contract.

**Alternative considered:** test against `9092` directly and skip if the port is busy. Rejected — flaky tests are worse than slightly more setup code.

### Decision 4: Default-profile companion test asserts the bean is *absent*

A second test, `H2ServerInitializerDefaultProfileTest`, runs without `@ActiveProfiles("dev")` and asserts `applicationContext.getBeansOfType(H2ServerInitializer.class).isEmpty()`. This is the production-safety regression guard.

**Why:**

- `@Profile("dev")` is easy to remove during a future refactor (e.g. someone wants to share the TCP server with another dev-like profile). Without a test pinning the absence-under-default behaviour, an accidental removal of the guard would silently expose the port in production.
- The test runs in milliseconds (no Tomcat, no JPA writes — just `getBeansOfType`); it's a cheap insurance policy.

## Risks / Trade-offs

[Risk] A developer with another H2 server on port `9092` sees the application fail to boot with `BindException`. → **Mitigation accepted**: this is the existing behaviour of the switch (the change makes it more visible, not new). The remedy is `--bank-core.h2.tcp-server.port=<free>` per session. The startup log line names port `9092` explicitly so the developer can correlate the error.

[Risk] The TCP server binds with `-tcpAllowOthers` (existing code, unchanged), making the port reachable from other machines if the host is not firewalled. → **Mitigation accepted**: the `@Profile("dev")` guard ensures this is dev-only; the dev profile is documented as developer-laptop-only and never deployed to a shared environment. If a future change ever runs the dev profile on a shared host, that change owns the responsibility to flip `-tcpAllowOthers` off.

[Risk] An external tool issues `ALTER TABLE` against the live application DB and breaks Hibernate's `ddl-auto=validate` on the next read. → **Mitigation documented**: the YAML comment block explicitly warns that the TCP server exposes the live DB and that schema changes from external clients will break the running application. Operators inspecting tables: read-only. Writes: at your own risk.

[Risk] The new randomised-port test races other tests competing for the same port-allocation strategy and occasionally picks a port that's between `ServerSocket(0).close()` and `Server.createTcpServer(...).start()` and gets stolen by another process. → **Mitigation accepted**: the window is microseconds; the test will fail loudly if it ever occurs (`BindException`), which is the right outcome. The alternative — running the test serially with a fixed port — adds infrastructure for a problem that has not been observed.

[Risk] The new dev-profile test starts a real H2 TCP server inside the JVM. Other tests run in the same JVM share the Logback context and the H2 driver. → **Mitigation**: the test uses `webEnvironment = NONE` so no Tomcat starts; `@DirtiesContext(classMode = AFTER_CLASS)` ensures the server is stopped via `@PreDestroy` before the next test class's context loads (preventing port leaks across the test suite). The default-profile companion test verifies the absence is achieved when the test class context does not declare the dev profile.

## Open Questions

None — the change is a one-line YAML flip plus a log-line edit plus two small integration tests. All trade-offs are bounded.
