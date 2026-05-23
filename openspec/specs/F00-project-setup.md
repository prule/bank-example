# F00 — Project Setup

## Summary

Foundational scaffolding spec. Establishes a runnable, empty Spring Boot service with the right toolchain, multi-module Gradle layout, clean-architecture boundaries enforced by tests, schema managed by Flyway, health surfaced via Actuator, and scheduling/async enabled — so every feature spec from F01 onward has somewhere to live. This spec does NOT add any business behaviour. When it ships, the service starts, serves only `/actuator/health`, and runs no business logic.

## User story

As the engineer about to implement F01–F11, I want a clean, conventional project skeleton already in place — multi-module build, profiles, migrations, schedulers, package layout, boundary tests — so that I am never blocked by "where does this go" or "why does the build let me import that" while building features.

## In scope

- Build system, JDK toolchain, dependency versions.
- **Multi-module Gradle layout** that physically separates domain, application, infrastructure, and bootstrap concerns.
- Application bootstrap entry point.
- Spring profiles for dev and test, with their respective datasources.
- JPA / Hibernate wiring with **Flyway-managed schema** (no `ddl-auto: create-drop`).
- Scheduling and async execution enablement.
- The mount point and gating for the OpenAPI docs UI (the contract itself is F04).
- The local H2 access affordances (web console, optional TCP server).
- **Spring Boot Actuator** with `/actuator/health` exposed.
- **ArchUnit** boundary tests enforcing the module dependency arrows.
- A runnable, empty service that responds to `GET /actuator/health` with `200`.

## Out of scope

- The OpenAPI contract content and code generation pipeline — F04 owns that.
- Any business endpoint or domain class — F01+ own those.
- CI configuration, container images, deployment topology.
- Production-grade datasource (Postgres) — the dev profile uses H2; production wiring is a future spec.
- Authentication, authorization.

## Functional requirements

### Toolchain & build

- The project builds on JDK 17 (or later LTS) declared via Gradle toolchains, not inherited from the developer's environment.
- A single root Gradle build script (Kotlin DSL) drives the whole project. Module-level build scripts contain only what is specific to that module.
- A fresh checkout builds with one command (`./gradlew build`) and no IDE setup.
- Recent stable Spring Boot 3.x. Dependency management delegated to the Spring Boot BOM; ad-hoc version pinning forbidden unless overriding the BOM.

### Multi-module layout

Gradle multi-project build. Four modules. Each module's build script declares only the dependencies it actually needs — no umbrella "everything everywhere" pulled in by the root.

```
.
├── settings.gradle.kts           (includes the four modules)
├── build.gradle.kts              (root: only common config)
├── domain/                       (pure types and rules)
├── application/                  (use cases, ports, commands, results)
├── infrastructure/               (JPA repos, REST controllers, schedulers, OpenAPI generation)
└── bootstrap/                    (main(), wiring, profiles, Flyway, Actuator)
```

Module rules — enforced by Gradle dependencies AND by ArchUnit tests:

- **`domain`** — pure Java. Allowed dependencies: JDK only. NOT allowed: Spring, Jakarta Persistence, Hibernate, Jackson, any framework. Contains F01 / F02 types.
- **`application`** — pure Java + `domain`. Allowed: SLF4J for logging. NOT allowed: Spring annotations on production code (test code may use Spring for integration tests), Jakarta Persistence, JPA, web. Contains use cases, **ports** (interfaces describing what infrastructure must provide — e.g. `AccountRepository`, `JournalRepository`), command and result types.
- **`infrastructure`** — depends on `application` and `domain`. Allowed: Spring, Spring Data JPA, web, OpenAPI generated code. Implements the ports declared in `application`. Hosts REST controllers, JPA entities (separate from domain types), scheduled job classes (thin shells over use cases — see `INTRODUCTION.md`), generated OpenAPI types.
- **`bootstrap`** — depends on everything. Hosts the `@SpringBootApplication` class, profile-specific configuration, Flyway configuration, Actuator configuration, `H2ServerInitializer`, the run script entry point.

Dependency direction is strictly inward: `bootstrap → infrastructure → application → domain`. No reverse edges. ArchUnit enforces this.

### Dependencies by module

- `domain`: nothing beyond JDK. Test deps: JUnit 5.
- `application`: `domain`, SLF4J API. Test deps: JUnit 5, Mockito.
- `infrastructure`:
  - `application`, `domain`.
  - `spring-boot-starter-web`.
  - `spring-boot-starter-data-jpa`.
  - `spring-boot-starter-validation`.
  - Whatever the OpenAPI generator (F04) needs at runtime.
  - H2 driver (runtime).
  - Test deps: `spring-boot-starter-test`.
- `bootstrap`:
  - `infrastructure` (transitively brings the rest).
  - `spring-boot-starter-actuator`.
  - `org.flywaydb:flyway-core`.
  - Test deps: `spring-boot-starter-test`, ArchUnit.

### Application entry point

- A single `@SpringBootApplication` class lives in `bootstrap`.
- `@EnableScheduling` and `@EnableAsync` declared on the entry point (so F10/F11 schedulers and transactional event listeners work without further wiring).
- Component-scan base packages explicitly listed so each Spring-aware module is picked up.

### Profiles & datasources

- **`default`** profile: H2 in-memory, **Flyway runs on boot**, JPA `ddl-auto: validate`. No dev affordances.
- **`dev`** profile: extends `default`. Enables H2 web console at `/h2-console`; optionally an H2 TCP server (`H2ServerInitializer`) so external SQL clients can attach.
- **`test`** profile: isolated H2 in-memory in PostgreSQL-compatibility mode. Flyway runs on test boot; `ddl-auto: validate`.
- No production profile in this iteration. A future spec wires Postgres.

### Schema management — Flyway

- Schema is managed by Flyway. **`ddl-auto` is `validate` everywhere** — JPA never creates or alters tables, only confirms the entities match what Flyway produced.
- Migrations live in `bootstrap/src/main/resources/db/migration/` following the standard Flyway naming (`V1__init.sql`, `V2__...sql`).
- F00 ships with one bootstrap migration that creates the tables the upcoming entities (F01, F02) will map to. (If F00 is to ship strictly before F01/F02, the initial migration can be a no-op placeholder and the first real schema migration arrives with F01 — pick one rule and apply consistently.)
- The legacy `sql/V1__init.sql` snapshot in the repo root is reference material only and is NOT used by Flyway.
- Flyway baseline-on-migrate is **off** for fresh environments and **on** for any environment that pre-existed Flyway adoption. Default for new branches: off.

### Scheduling and async

- `@EnableScheduling` and `@EnableAsync` on the bootstrap entry point.
- Default Spring scheduler is acceptable for F00. If F10/F11 ever need parallelism, a sized scheduler is added in this spec's territory (a `SchedulingConfigurer` in `bootstrap`).

### Health — Actuator

- `spring-boot-starter-actuator` is a dependency of `bootstrap`.
- Default exposed endpoints: `health` only. (`info`, `metrics`, etc. may be exposed in later specs; F00 keeps the surface minimal.)
- `/actuator/health` returns `200` with `{"status":"UP"}` on a healthy boot.
- The DB health indicator is enabled by default and reports `DOWN` if the datasource is unreachable.

### Boundary enforcement — ArchUnit

- ArchUnit added as a test dependency in `bootstrap` (or in a dedicated `architecture-tests` source set, at the project's discretion).
- A test class runs on every build and fails if any of the following holds:
  - A class under the `domain` module imports any of: `org.springframework.*`, `jakarta.persistence.*`, `org.hibernate.*`, `com.fasterxml.jackson.*`, `org.openapitools.*`, the generated DTO package.
  - A class under the `application` module imports any of: `org.springframework.*` (except `@Transactional` if explicitly allowed; default — disallow on production sources), `jakarta.persistence.*`, web/REST types, generated DTOs.
  - Any class in `infrastructure` or `bootstrap` is referenced from `domain` or `application`.
  - Any JPA entity (annotated `@Entity`) lives outside `infrastructure`.

### Package conventions within modules

- Root package: `com.bank.core` across all modules.
- `domain`: `com.bank.core.domain` — entities and value objects (F01, F02). No further sub-packaging required at this stage.
- `application`: `com.bank.core.application`, with sub-packages by use case (e.g. `transfer`, `accountopening`, `audit`) added as F05+ arrive.
- `infrastructure`: `com.bank.core.infrastructure` with sub-packages `persistence`, `web`, `scheduling`.
- `bootstrap`: `com.bank.core` for the `@SpringBootApplication` class; `com.bank.core.config` for configuration classes.
- Generated OpenAPI types: `com.bank.core.api` (interfaces) and `com.bank.core.dto` (models). Generated under `infrastructure/build/generated/openapi/...` and contributed to that module's source set.

### Configuration loading

- All tunables read from `application.yaml` and per-profile overrides. Environment variables override file values.
- No tunable hardcoded in Java.

### Logging

- Spring Boot defaults. No custom appenders configured here.

### Run script

- `run.sh` at the repo root with convenience targets (`bootRun`, `test`, `swagger`, `h2`). Optional but recommended; carry forward from v1.

## Acceptance criteria

1. **One-shot build.** A fresh checkout, with only a JDK installed, runs `./gradlew build` successfully without IDE setup.
2. **One-shot run.** `./gradlew :bootstrap:bootRun` starts the service against H2; Flyway runs its baseline migration; the service is healthy on `/actuator/health` within seconds.
3. **Health endpoint.** `GET /actuator/health` returns `200` with `{"status":"UP"}` and the DB component reports `UP`.
4. **Dev affordances gated.** Activating the `dev` profile exposes `/h2-console`. Under `default` or `test`, that URL is unreachable.
5. **Test profile isolated.** `./gradlew test` runs against the `test` profile's H2 instance, applies Flyway migrations into it, and does not interfere with a running `bootRun` process.
6. **Multi-module dependency arrows.** From `domain/src/main/java/...`, no `import` line references `org.springframework`, `jakarta.persistence`, `org.hibernate`, `com.fasterxml.jackson`, or the generated DTO package. Same check for `application` (allowing only `domain` + SLF4J). Verified by ArchUnit on every build.
7. **No upward imports.** No class in `domain` or `application` imports anything from `infrastructure` or `bootstrap`. Verified by ArchUnit.
8. **JPA entities in infrastructure.** Any class annotated `@Entity` lives in `infrastructure.persistence`. Verified by ArchUnit.
9. **Flyway in charge of schema.** Removing or renaming a column in a Flyway migration causes the service to fail to boot (because JPA `validate` no longer matches). Removing or renaming a column in a JPA entity without a corresponding migration causes the same failure.
10. **No `ddl-auto: create-drop`.** A grep of the codebase finds zero occurrences of `create-drop` or `create` for `ddl-auto`. The only value used is `validate`.
11. **Schedulers ready.** A throwaway `@Scheduled` log line, added temporarily for verification, fires on its declared cadence and is removed before merge.
12. **Env override.** Setting `SPRING_DATASOURCE_URL` in the environment overrides the file-configured URL, confirming environment-variable precedence.

## Dependencies

None. F00 is the first spec to implement on a fresh branch.

## Notes for downstream specs

- **F01 / F02** entities are pure Java types in `domain`. The JPA-annotated mirror classes (if you choose the "separate persistence model" route) live in `infrastructure.persistence`; mappers translate between them. If you instead choose to annotate the domain types directly (and break the boundary rule), that decision must be re-litigated against F00's ArchUnit rule — do not silently disable the check.
- **F03 / F04** error envelope and OpenAPI generation slot into the `infrastructure` module. Generated classes are not part of `domain` or `application`.
- **F10 / F11** schedulers are Spring classes in `infrastructure.scheduling` whose only job is to invoke a plain-Java use case in `application`. Per `INTRODUCTION.md`: orchestration shell in `infrastructure`, business decision in `application`.

## Open questions

None blocking. Decisions previously open are now baked into requirements above: Actuator (in), Flyway (in, `ddl-auto: validate`), ArchUnit (in), multi-module Gradle (in, four modules).

Items deferred to later specs:
- Production datasource wiring (Postgres) — future spec.
- Migration tool's behaviour against an existing non-empty prod database (baseline-on-migrate policy) — revisit at production spec.
- Whether `application` is allowed to use `org.springframework.transaction.annotation.@Transactional` directly, or whether transactions are declared in `infrastructure`. Default in this spec: disallow on `application` production sources; transactions are declared by the use-case caller in `infrastructure`.
