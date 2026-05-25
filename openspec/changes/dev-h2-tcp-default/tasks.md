## 1. Configuration

- [ ] 1.1 Modify `bootstrap/src/main/resources/application-dev.yaml`. Replace the existing `bank-core.h2.tcp-server` block with one whose `enabled` value is `true` (was `false`). Keep `port: 9092`. Replace the existing one-line comment with a multi-line block documenting:
  - The TCP server exposes the live application DB (the `mem:bankcore` instance the application's Hikari pool is using). External tooling connecting via `jdbc:h2:tcp://localhost:9092/mem:bankcore` with user `sa` and empty password sees the same Flyway-managed tables and the same uncommitted data.
  - Production safety: the bean is `@Profile("dev")`-gated, so flipping this property has no effect outside the dev profile.
  - Per-session override to restore the old "off" behaviour: `--bank-core.h2.tcp-server.enabled=false` on the command line.
  - Per-session recipe to persist data across restarts: override `spring.datasource.url` to `jdbc:h2:file:./build/h2-dev;DB_CLOSE_DELAY=-1`; the TCP URL fragment then becomes `jdbc:h2:tcp://localhost:9092/file:./build/h2-dev`.
- [ ] 1.2 Confirm `bootstrap/src/main/resources/application.yaml` does NOT mention `bank-core.h2.tcp-server` (design.md Decision 1 — dev-only property).
- [ ] 1.3 Confirm `bootstrap/src/test/resources/application-test.yaml` does NOT mention `bank-core.h2.tcp-server`.

## 2. Code

- [ ] 2.1 Modify `bootstrap/src/main/java/com/bank/core/config/H2ServerInitializer.java`. Update the `@PostConstruct` log statement from `log.info("H2 TCP server started on port {}", port)` to `log.info("H2 TCP server started on port {} (connect with: jdbc:h2:tcp://localhost:{}/mem:bankcore, user sa, no password)", port, port)`. The format string takes two `{}` placeholders both bound to `port`. No other logic changes — the `@Component`, `@Profile("dev")`, `@ConditionalOnProperty`, constructor, `@PreDestroy` are all unchanged.

## 3. Tests

- [ ] 3.1 Create `bootstrap/src/test/java/com/bank/core/config/H2ServerInitializerDevProfileTest.java`:
  - `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` so no Tomcat starts.
  - `@ActiveProfiles("dev")` so the `@Profile("dev")` guard fires.
  - `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)` so the `@PreDestroy` stop runs before another test class loads.
  - `@DynamicPropertySource` static method picks an OS-assigned free port via `new java.net.ServerSocket(0)` (immediately closing it so the port is free for H2 to bind), then registers `bank-core.h2.tcp-server.port` → that port and stashes it in a static field for the test methods.
  - `@Autowired ApplicationContext context`.
  - Test `tcpServerBeanIsConstructed`: assert `context.getBeansOfType(H2ServerInitializer.class)` returns a map of size 1.
  - Test `externalJdbcClientCanConnectAndQuery`: open `DriverManager.getConnection("jdbc:h2:tcp://localhost:" + port + "/mem:bankcore", "sa", "")`, prepare `SELECT 1`, assert result is `1`. Then prepare `SELECT COUNT(*) FROM account` and assert it executes without error (count value irrelevant — the assertion is that the Flyway-managed table exists in the TCP-exposed instance).
  - Test `startupLogIncludesConnectionString`: attach a Logback `ListAppender` to the `H2ServerInitializer` logger before the test method runs (the `@PostConstruct` log already fired during context init — so this test reads `context.getBean(H2ServerInitializer.class)`'s log indirectly via a captured root-logger appender installed via `@BeforeAll` static initialiser). Assert the captured log contains exactly one INFO line whose formatted message contains both `H2 TCP server started on port` and `jdbc:h2:tcp://localhost:` and `mem:bankcore`.

    NOTE on timing: the `@PostConstruct` log fires once during context init, BEFORE any test method runs. Logback's `LoggerContext.reset()` (Spring Boot's logging init) happens even earlier, before the bean is constructed. So a `@BeforeAll` static appender attached to the `H2ServerInitializer` logger BEFORE the context loads is the natural capture point — Spring's test framework instantiates the test class after `@BeforeAll`, so the static initialiser fires after `@BeforeAll`. To work around this, register the appender in a `static {}` block that runs at class load (before any Spring lifecycle), targeted at the named logger `com.bank.core.config.H2ServerInitializer`.
- [ ] 3.2 Create `bootstrap/src/test/java/com/bank/core/config/H2ServerInitializerDefaultProfileTest.java`:
  - `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`.
  - `@ActiveProfiles("test")` (the test profile is the closest "non-dev" the test suite uses).
  - `@Autowired ApplicationContext context`.
  - Test `tcpServerBeanIsAbsent`: assert `context.getBeansOfType(H2ServerInitializer.class)` is empty.
  - Test `noProcessListensOnDefaultH2Port`: assert that a `new Socket("localhost", 9092)` either throws `ConnectException` immediately or — if a developer's local `bootRun` happens to be running — the test logs a warning and skips the assertion (use `Assumptions.assumeFalse(...)` with the developer-friendly skip message). This test is the absence-regression guard; flakiness against a developer's running `bootRun` is worse than the test being slightly forgiving.

## 4. End-of-change verification

- [ ] 4.1 Run `./gradlew :bootstrap:test --tests "com.bank.core.config.*"`. Both new tests pass; existing config tests still pass.
- [ ] 4.2 Run `./gradlew clean build`. All modules green; F00 ArchUnit suite still passes; no new Gradle dependency.
- [ ] 4.3 Run `openspec change validate dev-h2-tcp-default --strict`. Confirm clean output.
- [ ] 4.4 Run `openspec validate --specs`. Confirm all 12 capability specs validate.
- [ ] 4.5 Manual smoke: `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'`. Within 15 seconds, captured stdout contains exactly one line matching `H2 TCP server started on port 9092 (connect with: jdbc:h2:tcp://localhost:9092/mem:bankcore, user sa, no password)`. From a separate terminal, run `jdbc:h2:tcp://localhost:9092/mem:bankcore` with any JDBC client; confirm tables `account`, `journal_entry`, `ledger_movement`, `audit_checkpoint` are visible and that `SELECT account_number, balance, status FROM account` returns the F09-seeded rows (clearing + CUST-1001 / CUST-1002 / CUST-1003).
- [ ] 4.6 Manual smoke: `./gradlew :bootstrap:bootRun` (no profile, no env var). Within 15 seconds, captured stdout contains NO line whose message contains `H2 TCP server started`. From a separate terminal, `nc -z localhost 9092` reports the connection refused (no process listening on `9092`).
