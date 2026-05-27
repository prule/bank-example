## 1. Gradle Scaffolding & Multi-Module Setup

- [x] 1.1 Create root settings.gradle.kts and declare modules: domain, application, infrastructure, and bootstrap.
- [x] 1.2 Create root build.gradle.kts and configure Java 25 toolchain, Spring Boot dependency management, and repositories.
- [x] 1.3 Create subproject directories and their individual build.gradle.kts files with explicit inward-pointing dependencies.

## 2. Profile-Aware Datasources and H2 Configuration

- [x] 2.1 Create application configuration files (application.yaml, application-dev.yaml, application-test.yaml) in the bootstrap module.
- [x] 2.2 Configure default, dev, and test profiles with their respective H2 database URLs and ddl-auto=validate properties.
- [x] 2.3 Implement the H2ServerInitializer bean in the infrastructure or bootstrap module, gated by @Profile("dev") and @ConditionalOnProperty to start/stop the H2 TCP server on port 9092.

## 3. Database Schema Ownership (Flyway)

- [x] 3.1 Add Flyway dependency to the bootstrap build configuration.
- [x] 3.2 Create the initial migration V1__init.sql in bootstrap/src/main/resources/db/migration/ defining tables: account, journal_entry, ledger_movement, and audit_checkpoint.

## 4. Bootstrap and Entry Point Setup

- [x] 4.1 Create the main application entry point class com.bank.core.bootstrap.BankCoreApplication in the bootstrap module.
- [x] 4.2 Annotate BankCoreApplication with @SpringBootApplication, @EnableScheduling, and @EnableAsync.
- [x] 4.3 Configure Spring Boot Actuator in application.yaml to expose only the health endpoint and verify it reports database health status.

## 5. Architectural Boundary Testing (ArchUnit)

- [x] 5.1 Add ArchUnit test dependency to the bootstrap build configuration.
- [x] 5.2 Write ArchUnit test suite in bootstrap/src/test/java/ to strictly enforce inward dependency directions and verify zero framework/database dependency imports in domain and application.
