## 1. Gradle skeleton

- [ ] 1.1 Add Gradle wrapper at the repo root (`./gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`) pinned to a current Gradle 8.x release that supports JDK 25 toolchains.
- [ ] 1.2 Create `settings.gradle.kts` at the repo root declaring `rootProject.name = "bank-core"` and `include("domain", "application", "infrastructure", "bootstrap")`.
- [ ] 1.3 Create root `build.gradle.kts` that applies `java` and `io.spring.dependency-management` plugins under `subprojects { }`, sets the Java toolchain to JDK 25, imports the Spring Boot 3.3.x BOM, and configures `tasks.withType<Test> { useJUnitPlatform() }`.
- [ ] 1.4 Create `gradle.properties` with `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.jvmargs=-Xmx2g`.
- [ ] 1.5 Confirm `./gradlew help` succeeds against a clean checkout with only any JDK installed.

## 2. Module: domain

- [ ] 2.1 Create `domain/build.gradle.kts` with `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` and `dependencies { testImplementation("org.junit.jupiter:junit-jupiter") }` — no other dependencies.
- [ ] 2.2 Create `domain/src/main/java/com/bank/core/domain/.gitkeep` so the source root exists.
- [ ] 2.3 Create `domain/src/test/java/com/bank/core/domain/.gitkeep`.

## 3. Module: application

- [ ] 3.1 Create `application/build.gradle.kts` depending on `project(":domain")` and `org.slf4j:slf4j-api`, with test deps `junit-jupiter` and `mockito-core`.
- [ ] 3.2 Create `application/src/main/java/com/bank/core/application/.gitkeep`.
- [ ] 3.3 Create `application/src/test/java/com/bank/core/application/.gitkeep`.

## 4. Module: infrastructure

- [ ] 4.1 Create `infrastructure/build.gradle.kts` depending on `project(":application")`, `project(":domain")`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, with `runtimeOnly("com.h2database:h2")` and test dep `spring-boot-starter-test`.
- [ ] 4.2 Create empty source-root marker files under `infrastructure/src/main/java/com/bank/core/infrastructure/{persistence,web,scheduling}/.gitkeep`.
- [ ] 4.3 Create `infrastructure/src/test/java/com/bank/core/infrastructure/.gitkeep`.

## 5. Module: bootstrap

- [ ] 5.1 Create `bootstrap/build.gradle.kts` applying the `org.springframework.boot` plugin, depending on `project(":infrastructure")`, `spring-boot-starter-actuator`, `org.flywaydb:flyway-core`, with test deps `spring-boot-starter-test` and `com.tngtech.archunit:archunit-junit5`.
- [ ] 5.2 Create `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` annotated with `@SpringBootApplication(scanBasePackages = "com.bank.core")`, `@EnableScheduling`, `@EnableAsync`, with a standard `main` method delegating to `SpringApplication.run`.
- [ ] 5.3 Create `bootstrap/src/main/resources/application.yaml` with `default` profile values: H2 in-memory datasource (`jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`), Flyway enabled, `spring.jpa.hibernate.ddl-auto: validate`, `spring.h2.console.enabled: false`, `management.endpoints.web.exposure.include: health`.
- [ ] 5.4 Create `bootstrap/src/main/resources/application-dev.yaml` overriding only `spring.h2.console.enabled: true` and the optional H2 TCP server port.
- [ ] 5.5 Create `bootstrap/src/test/resources/application-test.yaml` with an isolated H2 in PostgreSQL-compatibility mode (`jdbc:h2:mem:bankcore-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`) and the same `ddl-auto: validate`.
- [ ] 5.6 Create `bootstrap/src/main/resources/db/migration/V1__init.sql` as a placeholder Flyway migration (single SQL comment).
- [ ] 5.7 Create `bootstrap/src/main/java/com/bank/core/config/H2ServerInitializer.java` annotated with `@Component` and `@Profile("dev")` that starts an H2 TCP server on the configured port. Mark optional and skip if it complicates the build.

## 6. ArchUnit boundary tests

- [ ] 6.1 Create `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java` using `ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests).importPackages("com.bank.core")`.
- [ ] 6.2 Add rule: `domain` MUST NOT depend on `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml.jackson..`, `org.openapitools..`, `com.bank.core.dto..`, `com.bank.core.api..`.
- [ ] 6.3 Add rule: `application` MUST NOT depend on `org.springframework..`, `jakarta.persistence..`, `com.bank.core.infrastructure..`, `com.bank.core.dto..`, `com.bank.core.api..`.
- [ ] 6.4 Add rule: `domain` and `application` MUST NOT depend on `com.bank.core.infrastructure..` or `com.bank.core.config..`.
- [ ] 6.5 Add rule: classes annotated `@jakarta.persistence.Entity` MUST reside in `com.bank.core.infrastructure.persistence..`.

## 7. Verification

- [ ] 7.1 Run `./gradlew build` — all modules compile, ArchUnit tests pass.
- [ ] 7.2 Run `./gradlew :bootstrap:bootRun &` then `curl http://localhost:8080/actuator/health` — confirm `200 {"status":"UP"}` with `db` component `UP`; stop the service.
- [ ] 7.3 Run the service with `--spring.profiles.active=dev` and confirm `GET /h2-console` returns 200 or 302; under default, confirm 404.
- [ ] 7.4 Grep the codebase for `ddl-auto` — every occurrence is `validate`; grep for `create-drop` and `create` returns zero matches against `ddl-auto`.
- [ ] 7.5 Temporarily add a `@Component` import in a class under `com.bank.core.domain`, run `./gradlew :bootstrap:test`, confirm ArchUnit fails; revert.
- [ ] 7.6 Temporarily declare a `@Scheduled(fixedRate=1000)` bean that logs a line, run `bootRun`, confirm the line fires on cadence, then remove.
- [ ] 7.7 Set `SPRING_DATASOURCE_URL=jdbc:h2:mem:override` in the environment, restart, confirm Spring binds the override.

## 8. Developer ergonomics

- [ ] 8.1 Add `run.sh` at the repo root with targets `build`, `test`, `run` (= `:bootstrap:bootRun --args='--spring.profiles.active=dev'`), `swagger` (placeholder), `h2` (placeholder). Make it executable.
- [ ] 8.2 Update `.gitignore` to exclude `build/`, `.gradle/`, IDE state files generated by the new build (keep existing entries).
