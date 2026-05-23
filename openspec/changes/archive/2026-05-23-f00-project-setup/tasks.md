## 1. Gradle skeleton

- [x] 1.1 Add Gradle wrapper at the repo root (`./gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`) pinned to a current Gradle 8.x release that supports JDK 25 toolchains.
- [x] 1.2 Create `settings.gradle.kts` at the repo root declaring `rootProject.name = "bank-core"` and `include("domain", "application", "infrastructure", "bootstrap")`.
- [x] 1.3 Create root `build.gradle.kts` that applies `java` and `io.spring.dependency-management` plugins under `subprojects { }`, sets the Java toolchain to JDK 25, imports the Spring Boot 3.3.x BOM, and configures `tasks.withType<Test> { useJUnitPlatform() }`.
- [x] 1.4 Create `gradle.properties` with `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.jvmargs=-Xmx2g`.
- [x] 1.5 Confirm `./gradlew help` succeeds against a clean checkout with only any JDK installed.

## 2. Module: domain

- [x] 2.1 Create `domain/build.gradle.kts` with `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` and `dependencies { testImplementation("org.junit.jupiter:junit-jupiter") }` — no other dependencies.
- [x] 2.2 Create `domain/src/main/java/com/bank/core/domain/.gitkeep` so the source root exists.
- [x] 2.3 Create `domain/src/test/java/com/bank/core/domain/.gitkeep`.

## 3. Module: application

- [x] 3.1 Create `application/build.gradle.kts` depending on `project(":domain")` and `org.slf4j:slf4j-api`, with test deps `junit-jupiter` and `mockito-core`.
- [x] 3.2 Create `application/src/main/java/com/bank/core/application/.gitkeep`.
- [x] 3.3 Create `application/src/test/java/com/bank/core/application/.gitkeep`.

## 4. Module: infrastructure

- [x] 4.1 Create `infrastructure/build.gradle.kts` depending on `project(":application")`, `project(":domain")`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, with `runtimeOnly("com.h2database:h2")` and test dep `spring-boot-starter-test`.
- [x] 4.2 Create empty source-root marker files under `infrastructure/src/main/java/com/bank/core/infrastructure/{persistence,web,scheduling}/.gitkeep`.
- [x] 4.3 Create `infrastructure/src/test/java/com/bank/core/infrastructure/.gitkeep`.

## 5. Module: bootstrap

- [x] 5.1 Create `bootstrap/build.gradle.kts` applying the `org.springframework.boot` plugin, depending on `project(":infrastructure")`, `spring-boot-starter-actuator`, `org.flywaydb:flyway-core`, with test deps `spring-boot-starter-test` and `com.tngtech.archunit:archunit-junit5`.
- [x] 5.2 Create `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` annotated with `@SpringBootApplication(scanBasePackages = "com.bank.core")`, `@EnableScheduling`, `@EnableAsync`, with a standard `main` method delegating to `SpringApplication.run`.
- [x] 5.3 Create `bootstrap/src/main/resources/application.yaml` with `default` profile values: H2 in-memory datasource (`jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`), Flyway enabled, `spring.jpa.hibernate.ddl-auto: validate`, `spring.h2.console.enabled: false`, `management.endpoints.web.exposure.include: health`.
- [x] 5.4 Create `bootstrap/src/main/resources/application-dev.yaml` overriding only `spring.h2.console.enabled: true` and the optional H2 TCP server port.
- [x] 5.5 Create `bootstrap/src/test/resources/application-test.yaml` with an isolated H2 in PostgreSQL-compatibility mode (`jdbc:h2:mem:bankcore-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`) and the same `ddl-auto: validate`.
- [x] 5.6 Create `bootstrap/src/main/resources/db/migration/V1__init.sql` as a placeholder Flyway migration (single SQL comment).
- [x] 5.7 Create `bootstrap/src/main/java/com/bank/core/config/H2ServerInitializer.java` annotated with `@Component` and `@Profile("dev")` that starts an H2 TCP server on the configured port. Mark optional and skip if it complicates the build.

## 6. ArchUnit boundary tests

- [x] 6.1 Create `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java` using `ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests).importPackages("com.bank.core")`.
- [x] 6.2 Add rule: `domain` MUST NOT depend on `org.springframework..`, `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml.jackson..`, `org.openapitools..`, `com.bank.core.dto..`, `com.bank.core.api..`.
- [x] 6.3 Add rule: `application` MUST NOT depend on `org.springframework..`, `jakarta.persistence..`, `com.bank.core.infrastructure..`, `com.bank.core.dto..`, `com.bank.core.api..`.
- [x] 6.4 Add rule: `domain` and `application` MUST NOT depend on `com.bank.core.infrastructure..` or `com.bank.core.config..`.
- [x] 6.5 Add rule: classes annotated `@jakarta.persistence.Entity` MUST reside in `com.bank.core.infrastructure.persistence..`.

## 7. Verification

- [x] 7.1 Run `./gradlew build` — all modules compile, ArchUnit tests pass.
- [x] 7.2 Run `./gradlew :bootstrap:bootRun &` then `curl http://localhost:8080/actuator/health` — confirm `200 {"status":"UP"}` with `db` component `UP`; stop the service.
- [x] 7.3 Run the service with `--spring.profiles.active=dev` and confirm `GET /h2-console` returns 200 or 302; under default, confirm 404.
- [x] 7.4 Grep the codebase for `ddl-auto` — every occurrence is `validate`; grep for `create-drop` and `create` returns zero matches against `ddl-auto`.
- [x] 7.5 Temporarily add a `@Component` import in a class under `com.bank.core.domain`, run `./gradlew :bootstrap:test`, confirm ArchUnit fails; revert. — Note: Gradle's module dependency rules already block Spring imports in `domain` at compile time. To exercise ArchUnit specifically, demonstrated with a misplaced `@Entity` in `com.bank.core.infrastructure.web` (compiles, ArchUnit rule #4 fails), then reverted.
- [x] 7.6 Temporarily declare a `@Scheduled(fixedRate=1000)` bean that logs a line, run `bootRun`, confirm the line fires on cadence, then remove.
- [x] 7.7 Set `SPRING_DATASOURCE_URL=jdbc:h2:mem:override` in the environment, restart, confirm Spring binds the override.

## 8. Developer ergonomics

- [x] 8.1 Add `run.sh` at the repo root with targets `build`, `test`, `run` (= `:bootstrap:bootRun --args='--spring.profiles.active=dev'`), `swagger` (placeholder), `h2` (placeholder). Make it executable.
- [x] 8.2 Update `.gitignore` to exclude `build/`, `.gradle/`, IDE state files generated by the new build (keep existing entries).

## Implementation notes / deviations from design

- **Gradle version**: Bootstrapped wrapper at Gradle 8.14.5 (not the system's 8.12.1, which couldn't parse JDK 25's version string). Daemon JVM is pinned to JDK 21 via `gradle/gradle-daemon-jvm.properties` because the Kotlin compiler bundled with Gradle 8.14.x still cannot parse "25.0.3"; the build's Java toolchain still compiles and tests against JDK 25.
- **Spring Boot version**: Used Spring Boot 3.4.5 (BOM + plugin) instead of the manifest's "3.3.x". 3.3.x doesn't run cleanly on JDK 25 bytecode. The manifest in `openspec/config.yaml` should be updated to reflect this; flagged separately.
- **ArchUnit version**: 1.4.2 (not 1.3.0 from the original spec). 1.3.0 and 1.4.0 silently skip JDK 25 bytecode (class major version 69); 1.4.2 reads it correctly.
- **`h2` dependency in bootstrap**: Added `implementation("com.h2database:h2")` to `bootstrap` (not just `runtimeOnly` in `infrastructure`) because `H2ServerInitializer` imports `org.h2.tools.Server`. If F09 or a later spec moves the TCP server elsewhere, this can become `runtimeOnly` again.
- **ArchUnit `allowEmptyShould(true)`**: Set on each rule because the `domain` and `application` modules currently have no production classes (F01/F02 add them). Without this, the rules fail "no classes matched". Will keep the flag so the same tests work both before and after the modules are populated.
- **Actuator health detail**: Set `management.endpoint.health.show-details: always` (not `when-authorized`) so the DB component is visible without security configured. For a study branch this is fine; a production spec should re-evaluate.
