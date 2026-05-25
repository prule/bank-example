## Why

A developer who runs `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'` today sees F09's seeded data in the application's H2 in-memory database — but if they reach for DBeaver, DataGrip, or `psql`-style tooling to inspect what `journal_entry` actually looks like or to ad-hoc query `ledger_movement`, there's no entry point. The Swagger UI is exposed (F00 / dev profile), the `/h2-console` web UI is exposed (existing dev-profile requirement), but neither of those is the developer's habitual database client.

The hooks already exist:

- `bootstrap/src/main/java/com/bank/core/config/H2ServerInitializer.java` (committed alongside F00) wraps `org.h2.tools.Server.createTcpServer(...)` in a `@Component @Profile("dev") @ConditionalOnProperty("bank-core.h2.tcp-server.enabled")` bean that starts an H2 TCP server on a configurable port (default `9092`) at `@PostConstruct` and stops it at `@PreDestroy`.
- The dev profile (`bootstrap/src/main/resources/application-dev.yaml`) declares the property block today: `bank-core.h2.tcp-server.enabled: false`, `port: 9092`. So everything required to flip the switch is in place — the bean is just dormant because nobody told it to start.
- The application's default JDBC URL is `jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`. H2's in-memory databases are JVM-scoped but *shared across all connections within the same JVM* — so the TCP server (running inside the application JVM) exposes the very same `bankcore` instance Hikari is reading and writing. External tooling connecting via `jdbc:h2:tcp://localhost:9092/mem:bankcore` therefore sees the application's live data with no schema duplication and no synchronisation concerns.

This change flips that switch ON by default in the `dev` profile so a developer running `--spring.profiles.active=dev` gets the TCP server for free, without having to remember a property override. Production safety is unaffected — the `@Profile("dev")` guard means the bean is never even constructed under the default profile, and the test profile inherits nothing from dev.

## What Changes

- Edit `bootstrap/src/main/resources/application-dev.yaml` to flip `bank-core.h2.tcp-server.enabled` from `false` to `true`. The port stays at `9092`. The YAML comment block above the property is rewritten to explain (a) what the TCP server exposes (the live `mem:bankcore` instance the application is using); (b) the JDBC URL external tools should use (`jdbc:h2:tcp://localhost:9092/mem:bankcore`); (c) the username (`sa`, password empty) and that JPA's `ddl-auto=validate` mode means external tools can write to the DB but the application's Hibernate session will reject the next read if the schema diverges (i.e. don't `ALTER TABLE` from your DB client and expect the app to keep running).
- Edit the existing `H2ServerInitializer.start()` log line from `H2 TCP server started on port {}` to `H2 TCP server started on port {} (connect with: jdbc:h2:tcp://localhost:{}/mem:bankcore, user sa, no password)` so a developer scanning startup output sees the exact connection string instead of having to find this documentation. The format-arg list grows from one to two; the message stays a single line.
- Add a brief operator-facing note to the existing `dev profile exposes h2-console` requirement in `openspec/specs/project-setup/spec.md` documenting the new sibling requirement: the dev profile ALSO starts the H2 TCP server on port `9092` by default. No behaviour change for the existing `/h2-console` scenario.
- **NO change** to `application.yaml` (the default-profile config). The TCP server bean is `@Profile("dev")`-gated, so the property is dead in the default profile and there's nothing to flip. We do NOT introduce the property in the default profile because doing so would imply it's a supported production knob, which it isn't.
- **NO change** to `application-test.yaml`. The test profile is also outside the `dev` profile guard. Even if a future test sets `bank.seed.enabled=true` etc., starting a TCP server inside the test JVM would race port `9092` across parallel test runs and provides no value to the test suite.
- **NO change** to the JDBC URL the application uses. It stays `jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1` — H2 in-memory databases are shared inside a single JVM, so the TCP server exposes the very same instance the application reads/writes. Switching to a file-backed URL (`jdbc:h2:file:...`) would persist data across restarts but is a separate trade-off the developer can take by setting `--spring.datasource.url=...` per session, and is explicitly out of scope here.
- Tests:
  - The existing `SwaggerUiDevProfileTest` continues to assert dev-profile bring-up; no change.
  - Add a new bootstrap-level integration test `bootstrap/src/test/java/com/bank/core/config/H2ServerInitializerDevProfileTest.java` (`@SpringBootTest(webEnvironment = NONE)`, `@ActiveProfiles("dev")`) that asserts:
    - `applicationContext.getBeansOfType(H2ServerInitializer.class)` is non-empty (the conditional bean is constructed).
    - A side-channel connection via `DriverManager.getConnection("jdbc:h2:tcp://localhost:9092/mem:bankcore", "sa", "")` returns a usable JDBC `Connection`; `SELECT 1` returns `1`; the connection sees the same `account` and `journal_entry` tables Flyway created (the same instance the application is using).
    - To avoid a port collision with a developer's local `bootRun` or with a parallel test process, the test overrides `bank-core.h2.tcp-server.port` to a high randomised port via `@DynamicPropertySource` (using `ServerSocket(0)` to pick an OS-assigned free port) and asserts the TCP connection works against THAT port. So the test verifies the wiring without forcing an absolute port.
  - Add a `default`-profile companion test `bootstrap/src/test/java/com/bank/core/config/H2ServerInitializerDefaultProfileTest.java` (`@SpringBootTest`, no active profile or just `test`) asserting that `applicationContext.getBeansOfType(H2ServerInitializer.class)` is empty — production safety regression guard.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `project-setup`: the `Profile-aware datasources` requirement gains a new scenario "dev profile starts the H2 TCP server on 9092 by default" that asserts the bean is constructed (vs the current state where the property defaults to `false` and the bean is dormant). The default- and test-profile scenarios are unchanged. The existing `dev profile exposes h2-console` scenario is unchanged.

## Impact

- **Code**:
  - `bootstrap/src/main/java/com/bank/core/config/H2ServerInitializer.java` (modified — log line carries the connection string).
- **Configuration**:
  - `bootstrap/src/main/resources/application-dev.yaml` (modified — `enabled: false` → `enabled: true`; expanded YAML comment).
- **Schema / migrations**: none.
- **OpenAPI**: none.
- **Build**: no new Gradle dependency. The H2 `org.h2.tools.Server` class is already on the classpath (H2 is a runtime dep of `bootstrap`).
- **Conventions**:
  - Reaffirms F00's `@Profile("dev")` gate on developer-convenience beans — no production exposure.
  - Reaffirms F00's "configuration knobs live in `application*.yaml`, not hard-coded" — the port stays externalised at `bank-core.h2.tcp-server.port`.
- **Open decisions**:
  - **No new open decision opened by this change.**
  - **Closed by this change implicitly**: "should the dev profile expose the H2 TCP server" — yes, by default.
- **Downstream**: none. F01-F11 do not depend on the TCP server in any way.
- **Backwards compat**: the only visible change is for `--spring.profiles.active=dev` users — they now get an extra startup log line and an open port `9092` on `localhost`. The dev profile already binds port `8080` (Tomcat), `9092` is just one more. Any developer who *doesn't* want the TCP server can set `--bank-core.h2.tcp-server.enabled=false` on the command line to restore the old behaviour. Production (default profile, no profile activation) sees no change.
- **Operational notes**:
  - The TCP server binds with `-tcpAllowOthers`, meaning a process on another machine can reach the port if `9092` is not firewalled. On a developer laptop this is fine; in any environment where `application-dev.yaml` would be active and `9092` is internet-reachable, this is a security concern. The change does not introduce that risk (the existing code already used `-tcpAllowOthers` when the switch was manually on); it just makes the switch on by default.
  - Port collisions: a developer who already has another H2 TCP server (or anything else) on `9092` will see a `java.net.BindException` during `@PostConstruct` and the application context will fail to start. The remedy is `--bank-core.h2.tcp-server.port=<free port>` on the command line. This is the same failure mode the existing switch produced and the change does not alter it.
  - Data lifetime: the H2 instance is still `mem:bankcore;DB_CLOSE_DELAY=-1`. Restarting the JVM drops every table; the TCP server has nothing to expose between restarts. Developers who want persistence across restarts can override `spring.datasource.url=jdbc:h2:file:./build/h2-dev`, which the TCP server's URL pattern (`jdbc:h2:tcp://localhost:9092/<sub-url>`) would map by replacing `mem:bankcore` with `file:./build/h2-dev` — out of scope here but explicitly documented in the YAML comment as a follow-up the developer can take per-session.
