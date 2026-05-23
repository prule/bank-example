## Why

The `v2-sdd` branch is greenfield — there is no Java source, no Gradle build, no migrations. Every feature spec from F01 onward presumes a runnable skeleton: multi-module Gradle, profile-aware Spring Boot wiring, Flyway-managed schema, Actuator health, ArchUnit boundary enforcement. Until that skeleton exists, nothing else can be implemented. F00 is the first spec on the build order for exactly this reason.

## What Changes

- Add a multi-module Gradle (Kotlin DSL) build with four modules: `domain`, `application`, `infrastructure`, `bootstrap`.
- Add a Spring Boot 3.x application entry point in `bootstrap` with `@EnableScheduling` and `@EnableAsync`.
- Add Spring profiles `default`, `dev`, `test` with H2 datasources; `dev` exposes `/h2-console`, `test` uses PostgreSQL-compatibility mode.
- Add Flyway with one initial migration; JPA `ddl-auto=validate` everywhere — JPA never mutates schema.
- Add Spring Boot Actuator with only `/actuator/health` exposed.
- Add ArchUnit tests in `bootstrap` enforcing module dependency arrows (`bootstrap → infrastructure → application → domain`, no upward edges, JPA entities confined to `infrastructure`).
- Add Gradle toolchain pinning JDK 25, BOM-managed Spring Boot dependency versions, no ad-hoc version pinning.
- Add a `run.sh` helper at repo root with `bootRun`, `test`, `swagger`, `h2` targets.

No business behaviour is added. The service starts, serves `/actuator/health`, and runs no business logic.

## Capabilities

### New Capabilities
- `project-setup`: Runnable empty Spring Boot service with multi-module Gradle layout, profile-aware H2 datasources, Flyway-managed schema, Actuator health, scheduling/async enablement, and ArchUnit-enforced module boundaries.

### Modified Capabilities
None. F00 is the first capability on this branch.

## Impact

- **Code**: Creates `domain/`, `application/`, `infrastructure/`, `bootstrap/` module trees with their `build.gradle.kts` and source roots. Adds root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, Gradle wrapper. Adds `bootstrap/src/main/resources/application.yaml` plus per-profile overrides, `bootstrap/src/main/resources/db/migration/V1__init.sql`, and the `@SpringBootApplication` class.
- **Build**: `./gradlew build` becomes the canonical entry point. `./gradlew :bootstrap:bootRun` starts the service. Gradle toolchain auto-provisions JDK 25 — developer JAVA_HOME no longer matters.
- **Dependencies**: Introduces Spring Boot 3.x BOM, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `flyway-core`, H2 driver, ArchUnit, JUnit 5, Mockito.
- **Downstream**: Unblocks F01–F11. Establishes the package layout (`com.bank.core.{domain,application,infrastructure,bootstrap,config,api,dto}`) every later spec must respect. ArchUnit rules become a build-time guardrail — violations from later specs fail the build.
- **No production datasource**, no auth, no CI config — all out of scope, owned by later specs.
