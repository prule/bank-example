## MODIFIED Requirements

### Requirement: Profile-aware datasources

The project SHALL define three Spring profiles — `default`, `dev`, `test` — each wired to its own H2 instance with Flyway-managed schema and JPA `ddl-auto=validate`. The `dev` profile SHALL additionally start an H2 TCP server on port `9092` (default, configurable via `bank-core.h2.tcp-server.port`) by default so a developer running `--spring.profiles.active=dev` can connect to the live application database from any standard JDBC client without setting extra flags. The TCP server SHALL be gated by both `@Profile("dev")` and `@ConditionalOnProperty("bank-core.h2.tcp-server.enabled")`, with the property defaulting to `true` in `application-dev.yaml`; the `default` and `test` profiles SHALL NOT start the TCP server under any property value because the `@Profile("dev")` guard prevents the bean from being constructed.

#### Scenario: default profile is minimal

- **WHEN** the service starts with no active profile
- **THEN** Spring resolves to the `default` profile, uses an in-memory H2 datasource, runs Flyway, sets `spring.jpa.hibernate.ddl-auto=validate`, and does not expose `/h2-console`

#### Scenario: dev profile exposes h2-console

- **WHEN** the service starts with `--spring.profiles.active=dev`
- **THEN** `GET /h2-console` returns a non-404 response (200 or 302)

#### Scenario: dev profile starts the H2 TCP server on 9092 by default

- **WHEN** the service starts with `--spring.profiles.active=dev` and no override of `bank-core.h2.tcp-server.enabled` or `bank-core.h2.tcp-server.port`
- **THEN** the Spring `ApplicationContext` contains exactly one `com.bank.core.config.H2ServerInitializer` bean; an external JDBC client connecting to `jdbc:h2:tcp://localhost:9092/mem:bankcore` with user `sa` and empty password successfully opens a connection and reads the `account`, `journal_entry`, `ledger_movement`, and `audit_checkpoint` tables (the same Flyway-managed tables the application's Hikari pool sees); the startup log emits exactly one INFO line whose formatted message contains `H2 TCP server started on port 9092` AND the connection-string fragment `jdbc:h2:tcp://localhost:9092/mem:bankcore`

#### Scenario: default profile does not start the H2 TCP server

- **WHEN** the service starts with no active profile (or `--spring.profiles.active=test`)
- **THEN** `applicationContext.getBeansOfType(H2ServerInitializer.class)` returns an empty map; no process is listening on port `9092` (verifiable by attempting `new java.net.Socket("localhost", 9092)` and observing `ConnectException`); the captured startup log contains no line whose message contains `H2 TCP server started`

#### Scenario: default profile does not expose h2-console

- **WHEN** the service starts with no active profile and `GET /h2-console` is requested
- **THEN** the response is HTTP `404`

#### Scenario: test profile is isolated

- **WHEN** `./gradlew test` runs the integration tests
- **THEN** tests execute under the `test` profile, against an isolated H2 instance in PostgreSQL-compatibility mode, with Flyway migrations applied at test bootstrap, and the running `bootRun` (if any) is not affected
