## Context

`v2-sdd` is a fresh branch with documentation only — no Java source, no Gradle scripts, no migrations. The downstream specs (F01–F11) assume a multi-module Gradle build with clean-architecture boundaries, Flyway-owned schema, and an ArchUnit safety net. Building any feature without those scaffolds in place would either leak Spring/JPA into the domain or silently let `ddl-auto` rewrite the schema. The F00 spec at [openspec/specs/F00-project-setup.md](openspec/specs/F00-project-setup.md) defines the contract; this design picks the concrete shape.

Constraints from the project manifest at [openspec/config.yaml](openspec/config.yaml): Java 25 (via Gradle toolchains, not the developer's `JAVA_HOME`), Spring Boot 3.3.x, Gradle Kotlin DSL. Repository root is the project root; existing top-level docs (`INTRODUCTION.md`, `REQUIREMENTS.md`, `ReadMe.md`, `Notes.md`) stay untouched.

## Goals / Non-Goals

**Goals:**
- A `./gradlew build` on a fresh checkout, JDK-only, succeeds.
- A `./gradlew :bootstrap:bootRun` starts a Spring Boot service that answers `GET /actuator/health` with `200 {"status":"UP"}` and a DB component reporting `UP`.
- Dependency arrows `bootstrap → infrastructure → application → domain` are enforced both by Gradle (modules only declare what they need) and by ArchUnit (one test class fails the build on any violation).
- `dev` profile exposes `/h2-console`; `default` and `test` do not.
- Flyway owns schema; JPA `ddl-auto=validate` everywhere.
- Scheduling and async are enabled at the entry point so F10/F11 schedulers slot in without further wiring.

**Non-Goals:**
- OpenAPI contract or code generation pipeline — F04.
- Any business endpoint, entity, or domain class — F01 onward.
- Production datasource (Postgres), CI, container images, auth.
- A custom thread pool for schedulers — Spring defaults are acceptable until F10/F11 demand otherwise.

## Decisions

### Module layout: four Gradle modules, separate Gradle projects

```
.
├── settings.gradle.kts
├── build.gradle.kts          (common config only)
├── gradle.properties
├── gradle/wrapper/...
├── domain/build.gradle.kts
├── application/build.gradle.kts
├── infrastructure/build.gradle.kts
└── bootstrap/build.gradle.kts
```

Each module has its own `src/main/java` and `src/test/java`. Root `build.gradle.kts` applies the Java toolchain plugin and shared test conventions via `subprojects { ... }`; module scripts only declare dependencies specific to that module. Alternatives considered: a single project with package-only separation (rejected — ArchUnit would be the only boundary, easy to relax silently); a buildSrc convention plugin (rejected — over-engineered for four modules).

### JDK 25 via Gradle toolchain, not environment

`java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` in the root build. Gradle auto-provisions the JDK if missing. The repo already has `.sdkmanrc` pinning `25.0.3-tem` for developers who use SDKMAN; the Gradle toolchain is the build-of-record. Rejected: `sourceCompatibility = "25"` alone (would inherit `JAVA_HOME`, defeating the "one-shot build" criterion).

### Spring Boot 3.3.x with BOM-managed versions

Apply `io.spring.dependency-management` and import `org.springframework.boot:spring-boot-dependencies` BOM. Module scripts declare starters without versions. Rejected: pinning each artefact (drift hazard, breaks the spec's "ad-hoc version pinning forbidden" rule).

### Module dependency rules

- `domain`: JDK only. Test deps: JUnit 5.
- `application`: `domain`, SLF4J API. Test deps: JUnit 5, Mockito.
- `infrastructure`: `application`, `domain`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, H2 driver (runtime). Test deps: `spring-boot-starter-test`.
- `bootstrap`: `infrastructure`, `spring-boot-starter-actuator`, `flyway-core`. Test deps: `spring-boot-starter-test`, ArchUnit.

`infrastructure` does NOT transitively expose Spring to `application` because `application` depends on `infrastructure` only at test time when it depends at all — and at F00 it doesn't. Gradle `implementation` (not `api`) keeps the surface tight.

### Package layout

Root package `com.bank.core` across all modules.

- `domain`: `com.bank.core.domain`
- `application`: `com.bank.core.application` (use-case sub-packages added as F05+ arrive)
- `infrastructure`: `com.bank.core.infrastructure.{persistence,web,scheduling}`
- `bootstrap`: `com.bank.core` for the `@SpringBootApplication` class; `com.bank.core.config` for configuration classes
- Generated OpenAPI (F04): `com.bank.core.api` (interfaces), `com.bank.core.dto` (models)

Spring component scan in the entry point lists `com.bank.core` so all modules are picked up; ArchUnit will catch any reverse imports.

### Profiles & datasources

- `default`: H2 in-memory `jdbc:h2:mem:bankcore;DB_CLOSE_DELAY=-1`, Flyway runs on boot, `ddl-auto=validate`, no dev affordances.
- `dev`: extends `default` via `spring.profiles.include`. Exposes `/h2-console` (Spring's `H2ConsoleAutoConfiguration` gated by `spring.h2.console.enabled=true` under `application-dev.yaml`). Optional `H2ServerInitializer` bean (a `@Component` annotated with `@Profile("dev")`) opens an H2 TCP server on a configurable port.
- `test`: separate H2 in PostgreSQL-compatibility mode (`MODE=PostgreSQL`), distinct DB name, Flyway runs at test bootstrap, `ddl-auto=validate`. Configured via `bootstrap/src/test/resources/application-test.yaml` and an `@ActiveProfiles("test")` on the integration test base class.

`/h2-console` reachability is enforced by `spring.h2.console.enabled` defaulting to `false` in `application.yaml`, overridden to `true` only in `application-dev.yaml`. The acceptance check is a `MockMvc` test: under `test` profile, `GET /h2-console` returns 404; under a `dev`-loaded `SpringBootTest`, it returns 200 or 302.

### Flyway

One initial migration at `bootstrap/src/main/resources/db/migration/V1__init.sql`. F00 chooses to ship V1 as a placeholder (a single comment) so the F01/F02 migrations can be V2/V3 owned by their specs. `flyway.baseline-on-migrate=false` (default in `application.yaml`); the legacy `sql/V1__init.sql` snapshot, if/when added, is documentation, not a Flyway source.

Alternative considered: ship V1 already containing the F01/F02 tables. Rejected — that bleeds F01/F02 scope into F00.

### Scheduling & async

`@EnableScheduling` and `@EnableAsync` on the `@SpringBootApplication` class. Default Spring scheduler. If F10/F11 need parallelism later, a `SchedulingConfigurer` added in `com.bank.core.config` is the agreed extension point — explicitly called out in the spec.

### ArchUnit boundary tests

Live in `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java`. Uses `ClassFileImporter().importPackages("com.bank.core")` so every module on the test classpath is scanned. Rules:

1. `noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..", "com.fasterxml.jackson..", "org.openapitools..", "com.bank.core.dto..", "com.bank.core.api..")`
2. `noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.bank.core.infrastructure..", "com.bank.core.dto..")` — with explicit `@Transactional` disallowed on production sources per the F00 spec's deferred decision (default = disallow).
3. `noClasses().that().resideInAnyPackage("..domain..", "..application..").should().dependOnClassesThat().resideInAnyPackage("com.bank.core.infrastructure..", "com.bank.core.config..")`
4. `classes().that().areAnnotatedWith(jakarta.persistence.Entity.class).should().resideInAPackage("com.bank.core.infrastructure.persistence..")`

These tests live in `bootstrap` so they have visibility into every module without inverting any dependency.

### Run script

`run.sh` at repo root, carried forward from v1, exposing:
- `./run.sh build`
- `./run.sh test`
- `./run.sh run` → `:bootstrap:bootRun --args='--spring.profiles.active=dev'`
- `./run.sh swagger` (no-op placeholder until F04 lands)
- `./run.sh h2` (no-op placeholder until F00's optional H2 TCP server is wired)

Optional per the spec. Included because it's a low-cost on-ramp for downstream specs.

### Configuration

All tunables live in `application.yaml` and `application-<profile>.yaml`. Environment variables override file values (Spring's default precedence). No constants hard-coded in Java.

## Risks / Trade-offs

- **Gradle toolchain JDK download** → first build on a clean machine takes longer while Gradle provisions JDK 25. Mitigation: documented in `run.sh` help and acceptance criterion #1 explicitly allows it.
- **ArchUnit false-positives on generated code** → F04's generated DTOs live in `com.bank.core.{api,dto}` and would trip rule 1 if `domain` ever imports them. Mitigation: rule explicitly bans those packages from `domain`, so this is the *intended* behaviour, not a false positive.
- **H2-console exposure under wrong profile** → security regression risk if a future spec sets `spring.h2.console.enabled=true` globally. Mitigation: a `MockMvc` test under `test` profile asserts `/h2-console` is 404; rule survives across specs.
- **`infrastructure` test classpath pulling Spring into `application`'s view** → ArchUnit scans the whole `com.bank.core` package, including test code. Mitigation: scope ArchUnit imports to `com.bank.core` *production* classes only via `ImportOption.DoNotIncludeTests`, so test code that touches Spring doesn't trip the rule.
- **Choosing JDK 25 over a current LTS (21)** → 25 is non-LTS at time of writing; some tooling lags. Mitigation: this is a study branch where the manifest pins JDK 25 explicitly; the toolchain provisioning isolates it from the developer's environment.

## Migration Plan

This is greenfield — there is nothing to migrate. The "deploy" step is `git push`; reviewers run `./gradlew build` and `./gradlew :bootstrap:bootRun` to verify acceptance.

Rollback: this change touches only new files plus the repo-root build scripts. `git revert` is sufficient.

## Open Questions

None blocking. The F00 spec's deferred items (production datasource, baseline-on-migrate policy for production, `@Transactional` on `application` sources) remain deferred to later specs.
