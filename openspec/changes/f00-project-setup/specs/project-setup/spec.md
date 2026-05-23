## ADDED Requirements

### Requirement: One-shot build on a fresh checkout

The project SHALL build successfully on a fresh checkout with only a JDK installed on the developer's machine, without any IDE setup.

#### Scenario: Fresh clone build
- **WHEN** a developer clones the repository on a machine with any installed JDK and runs `./gradlew build`
- **THEN** Gradle provisions JDK 25 via its toolchain, compiles every module, runs all tests, and exits with status `0`

#### Scenario: Toolchain isolates from environment JDK
- **WHEN** the developer's `JAVA_HOME` points to a JDK older than 25
- **THEN** `./gradlew build` still succeeds because the Gradle toolchain provisions JDK 25 independently of `JAVA_HOME`

### Requirement: One-shot run with healthy service

The project SHALL provide a single command to start the service against an embedded H2 instance, with Flyway applying its migrations on boot and the health endpoint reachable within seconds.

#### Scenario: bootRun starts a healthy service
- **WHEN** a developer runs `./gradlew :bootstrap:bootRun`
- **THEN** the service starts under the `default` profile, Flyway applies its migrations against H2, and within ten seconds `GET http://localhost:8080/actuator/health` returns HTTP `200` with body containing `"status":"UP"`

#### Scenario: Health endpoint reports DB status
- **WHEN** the service is running and `GET /actuator/health` is requested
- **THEN** the response includes a `db` component with status `UP`

### Requirement: Multi-module Gradle layout with enforced dependency arrows

The project SHALL be organised as a Gradle multi-project build with four modules — `domain`, `application`, `infrastructure`, `bootstrap` — whose dependency arrows point strictly inward (`bootstrap → infrastructure → application → domain`).

#### Scenario: Modules exist with their own build scripts
- **WHEN** the repository is inspected
- **THEN** `settings.gradle.kts` includes exactly the four modules `domain`, `application`, `infrastructure`, `bootstrap`, and each module has its own `build.gradle.kts` declaring only its own dependencies

#### Scenario: domain has no framework dependencies
- **WHEN** ArchUnit scans `com.bank.core.domain` production classes
- **THEN** no class imports any of: `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml.jackson..`, `org.openapitools..`, `com.bank.core.dto..`, `com.bank.core.api..`

#### Scenario: application is framework-light
- **WHEN** ArchUnit scans `com.bank.core.application` production classes
- **THEN** no class imports any of: `org.springframework..`, `jakarta.persistence..`, web/REST types, or generated DTO packages

#### Scenario: No upward imports from domain or application
- **WHEN** ArchUnit scans `com.bank.core.domain` and `com.bank.core.application` production classes
- **THEN** no class imports anything from `com.bank.core.infrastructure..` or `com.bank.core.config..`

#### Scenario: JPA entities confined to infrastructure
- **WHEN** ArchUnit scans all production classes for `@jakarta.persistence.Entity`
- **THEN** every class so annotated resides in `com.bank.core.infrastructure.persistence..`

### Requirement: Profile-aware datasources

The project SHALL define three Spring profiles — `default`, `dev`, `test` — each wired to its own H2 instance with Flyway-managed schema and JPA `ddl-auto=validate`.

#### Scenario: default profile is minimal
- **WHEN** the service starts with no active profile
- **THEN** Spring resolves to the `default` profile, uses an in-memory H2 datasource, runs Flyway, sets `spring.jpa.hibernate.ddl-auto=validate`, and does not expose `/h2-console`

#### Scenario: dev profile exposes h2-console
- **WHEN** the service starts with `--spring.profiles.active=dev`
- **THEN** `GET /h2-console` returns a non-404 response (200 or 302)

#### Scenario: default profile does not expose h2-console
- **WHEN** the service starts with no active profile and `GET /h2-console` is requested
- **THEN** the response is HTTP `404`

#### Scenario: test profile is isolated
- **WHEN** `./gradlew test` runs the integration tests
- **THEN** tests execute under the `test` profile, against an isolated H2 instance in PostgreSQL-compatibility mode, with Flyway migrations applied at test bootstrap, and the running `bootRun` (if any) is not affected

### Requirement: Flyway-owned schema with JPA validate

The project SHALL place schema authority with Flyway and SHALL configure JPA `ddl-auto=validate` in every profile, so JPA never creates or alters tables.

#### Scenario: ddl-auto is validate everywhere
- **WHEN** the repository is grepped for `ddl-auto`
- **THEN** every occurrence sets the value to `validate`, with zero occurrences of `create`, `create-drop`, `update`, or `none`

#### Scenario: Initial Flyway migration ships with the change
- **WHEN** the service starts under any profile
- **THEN** Flyway finds at least one migration under `bootstrap/src/main/resources/db/migration/` (starting with `V1__init.sql`) and applies it successfully

#### Scenario: Mismatch between entity and migration fails boot
- **WHEN** a JPA `@Entity` declares a column that no Flyway migration has produced (or vice versa)
- **THEN** the service fails to start with a Hibernate schema-validation error

### Requirement: Actuator health endpoint

The project SHALL include Spring Boot Actuator and expose only the `health` endpoint by default.

#### Scenario: Health endpoint is exposed
- **WHEN** the service is running and `GET /actuator/health` is requested
- **THEN** the response is HTTP `200` with JSON body containing `"status":"UP"`

#### Scenario: Other actuator endpoints are not exposed
- **WHEN** the service is running and `GET /actuator/metrics` is requested
- **THEN** the response is HTTP `404`

### Requirement: Scheduling and async enabled at the entry point

The project SHALL enable Spring scheduling and async execution at the `@SpringBootApplication` entry point, so downstream specs can declare `@Scheduled` and `@Async` methods without further wiring.

#### Scenario: @EnableScheduling and @EnableAsync are present
- **WHEN** the bootstrap entry point class is inspected
- **THEN** it is annotated with both `@EnableScheduling` and `@EnableAsync`

#### Scenario: A @Scheduled method fires on its declared cadence
- **WHEN** a temporary `@Scheduled(fixedRate = …)` bean is declared and the service runs
- **THEN** the method is invoked on its declared cadence

### Requirement: Configuration via files with environment override

The project SHALL load all tunables from `application.yaml` and per-profile overrides, with environment variables taking precedence over file values.

#### Scenario: Environment overrides datasource URL
- **WHEN** the service starts with `SPRING_DATASOURCE_URL` set in the environment
- **THEN** Spring binds that value over any URL configured in `application.yaml` or `application-<profile>.yaml`

#### Scenario: No tunable is hard-coded in Java
- **WHEN** the repository is inspected
- **THEN** no `@Value`, `@ConfigurationProperties`, or constant on a configuration class hard-codes a value that should be configurable (datasource URL, port, scheduler cadence)

### Requirement: ArchUnit boundary tests run on every build

The project SHALL include ArchUnit tests in the `bootstrap` module that fail the build whenever the module dependency arrows are violated.

#### Scenario: Build fails on a boundary violation
- **WHEN** a class is added under `com.bank.core.domain` that imports `org.springframework.stereotype.Component`
- **THEN** `./gradlew :bootstrap:test` fails with an ArchUnit violation message naming the offending class

#### Scenario: Build fails on an upward import
- **WHEN** a class under `com.bank.core.application` imports a class from `com.bank.core.infrastructure`
- **THEN** `./gradlew :bootstrap:test` fails with an ArchUnit violation
