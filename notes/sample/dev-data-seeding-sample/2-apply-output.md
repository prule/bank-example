```text
/opsx:apply dev-data-seeding
```
Using change: dev-data-seeding (override with `/opsx:apply <other>`).

## Implementing: dev-data-seeding (schema: spec-driven)

Progress: 0/61 tasks complete. All context artifacts are already loaded from the propose phase. Let me explore the existing code patterns before writing new files.

Now let me check the existing test config for the `test` profile and the application module's `build.gradle`.

Good. I have enough context. Let me start implementing. I'll begin with the application module value records (tasks 1.1–1.8).

Now the unit tests for these (tasks 1.5–1.8).

Now mark 1.1–1.8 complete in tasks.md and run the application module build to validate.

Tests pass. Now `OpensAccount` (2.1–2.2) and `SeedData` use case (3.1–3.10).

Now the `SeedDataTest`.

All application tests pass. Mark sections 2 and 3 complete.

Now sections 4 and 5 — infrastructure module: `SeedProperties` DTO and `SeedDataRunner`.

Now `SeedDataRunner` and its unit test.

Infrastructure tests pass. Mark sections 4 and 5 complete.

Section 6 — `SEED_DATA` env var alias.

Now wiring in `BankCoreApplication` (section 7) and yaml config (section 8).

Now config files (section 8).

Now compile-check that bootstrap is wired correctly, then implement the integration tests.

Now the env-postprocessor test and integration tests. Let me check what `bank-core.h2.tcp-server` references in application-dev.yaml — earlier I read it had that config. The new `bank:` block I added might conflict. Let me re-read.

Now the seed-OFF integration test (section 10) and ArchUnit test (section 12).

The seed never ran — Spring Boot's `SpringApplicationBuilder.properties(...)` uses default properties (low precedence) so my `application.yaml` values won. Let me switch to command-line args (high precedence).

The appender is being wiped when Spring Boot initializes its logging system. I need to attach it via a Spring `ApplicationListener` so it's added after Logback is reset but before the runner fires.

All 14 seed tests pass. Mark sections 6, 7, 8, 9, 10, 11, 12 complete and run the full suite.

6 still-pending checkboxes left (section 13). Now let me run the full Gradle build to validate everything, then handle 13.1, 13.5, 13.6.

The SeedDataOff test sees leftover data from prior runs in the JVM-shared H2 in-mem DB. Let me truncate at startup so the assertion is meaningful.

All bootstrap tests pass. Now the full clean build to confirm.

Clean build green. Now the openspec validation (13.5/13.6).

All validation green. Mark sections 13.1, 13.5, 13.6 done. Sections 13.2–13.4 are interactive bootRun smoke tests that need a human terminal session — I'll leave those unchecked and note them explicitly.

## Implementation Complete (with 3 paused manual smoke tests)

**Change:** dev-data-seeding
**Schema:** spec-driven
**Tests:** 58/61 task lines checked. Full `./gradlew clean build` green (15 new tests, all passing; F00 ArchUnit suite still passes; no new Gradle dependencies). `openspec change validate dev-data-seeding --strict` and `openspec validate --specs` both pass.

### What was built

- **Application module (Spring-free):** `CustomerSeed`, `ClearingAccountSeed`, `SeedPlan`, `SeedReport` (sealed), `OpensAccount` functional-interface adapter, and the `SeedData` use case — plus full unit-test coverage (5 test classes).
- **Infrastructure module:** `SeedProperties` `@ConfigurationProperties` DTO + `SeedDataRunner` (`@Component @ConditionalOnProperty @ApplicationRunner`) with Logback-based unit tests.
- **Bootstrap:** `SeedDataEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) aliasing `SEED_DATA` → `bank.seed.enabled` with low precedence; updated `BankCoreApplication` wires `SeedPlan` and `SeedData` beans (both gated); `application.yaml` adds the production-safe `bank.seed.enabled: false` default; `application-dev.yaml` ships the three-customer dev plan.
- **Integration tests:** `SeedDataRunnerIntegrationTest` (seed-on happy path + idempotent re-run), `SeedDataOffIntegrationTest` (no beans, no log lines, isolated H2 URL), `SeedDataFailureIntegrationTest` (programmatic `SpringApplicationBuilder` to capture the `ApplicationRunner` abort and inspect partial-seed state via side-channel JDBC), and `SeedArchUnitTest` pinning the infrastructure-package boundary.

### Three tasks left unchecked

13.2 / 13.3 / 13.4 are interactive `:bootstrap:bootRun` smoke tests (start the server, hit `/api/v1/accounts/...` with `curl`, `Ctrl-C` and restart with a file-backed H2 URL to observe the skip). These need a human terminal — happy to walk you through them if you'd like.

Run `/opsx:archive dev-data-seeding` to finalise the change once you're happy.
