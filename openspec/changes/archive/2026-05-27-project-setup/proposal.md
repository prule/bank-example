## Why

Establishing a robust, clean, and isolated architectural foundation is a prerequisite for spec-driven development. This change introduces the scaffolding—including a multi-module Gradle layout, profile-aware H2 datasources, Flyway-managed schema, Actuator health endpoints, scheduling/async enablement, and ArchUnit-enforced boundaries—necessary to build all subsequent bank-core features on a reliable, decoupled, and verifiable base.

## What Changes

- **Multi-Module Gradle Layout**: Establish four modules: `domain`, `application`, `infrastructure`, and `bootstrap`.
- **Toolchain isolation**: Enforce Java 25 toolchain isolation in Gradle configuration.
- **Profile-Aware Datasources**:
  - `default`: In-memory H2, JPA validation, Flyway, H2 Console disabled.
  - `dev`: In-memory H2, JPA validation, Flyway, H2 Console enabled, and H2 TCP server starting on port 9092.
  - `test`: Isolated H2, PostgreSQL-compatibility mode, Flyway applied, H2 Console and H2 TCP server disabled.
- **Schema Management**: Direct Flyway to own schema migrations; configure JPA with `ddl-auto=validate` across all profiles.
- **Actuator Health**: Expose only the `/actuator/health` endpoint with a DB status health indicator.
- **Async/Scheduling Support**: Enable scheduling (`@EnableScheduling`) and asynchronous execution (`@EnableAsync`) at the entry point.
- **ArchUnit Boundary Verification**: Introduce ArchUnit tests in the `bootstrap` module to strictly enforce inward-pointing dependencies: `bootstrap -> infrastructure -> application -> domain`.

## Capabilities

### New Capabilities
- `project-setup`: Multi-module Gradle build layout, profile-aware datasources, Flyway schema validation, Actuator health, and ArchUnit boundary enforcement.

### Modified Capabilities
<!-- None -->

## Impact

- **Code Structure**: Establishes the entire repository structure. Future features will implement code inside the specific module matching their architectural layer.
- **Dependencies**: All sub-projects are declared under Gradle.
- **CI/CD / Builds**: Establishes the standard `./gradlew build` boundary.
