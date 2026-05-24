## Why

F00 has landed: the multi-module Gradle skeleton, Spring Boot bootstrap, Flyway and ArchUnit guardrails are in place. Every API capability that follows (account lookup, fund transfer, account opening) needs a single, durable answer to "what does the public HTTP surface look like?" before its controllers can be written. The project manifest names `contract-first-api` as one of the five spine concepts: the OpenAPI document is the source of truth, server stubs are generated from it, and hand-written controllers implement generated interfaces. F04 establishes that pipeline now so that F03/F05/F06/F08 can each ship a contract slice without each one re-inventing the build wiring.

## What Changes

- Add an OpenAPI 3.x document under `bootstrap/src/main/resources/openapi/` as the canonical contract, structured as a root file that `$ref`s per-path and per-schema files so individual capability changes diff cleanly.
- Wire an OpenAPI code generator into the Gradle build for the `infrastructure` module so generation runs automatically before Java compilation, into a generated-sources output folder under the module's build directory.
- Configure the generator to produce **interfaces only** (one Java interface per OpenAPI tag) and **DTO classes** (one per schema), into dedicated packages `com.bank.core.api` (interfaces) and `com.bank.core.dto` (models). No generated controller bodies are wired into Spring.
- Serve the canonical OpenAPI document at a stable runtime URL (`/v3/api-docs`) so docs UIs and external clients fetch the exact file the build consumed.
- Under the `dev` profile, expose a Swagger UI (or equivalent) that loads its definition from the canonical contract URL — not from runtime annotation scanning.
- Define the error envelope schema (the contract used by F03) **once** under `components.schemas` and `$ref` it from every `4xx`/`5xx` response across every path declared in this change.
- Seed the contract with the bare minimum needed to validate the pipeline end-to-end: one trivial tagged operation (e.g. a `GET /v3/api-docs` health-style ping or the contract URL itself) and the shared error schema. The real endpoint contracts arrive in F03/F05/F06/F08.
- Update the ArchUnit boundary tests (already added in F00) — no rule changes are needed because the F00 rules already ban `com.bank.core.{dto,api}` from `domain` and `application`. F04 makes those rules load-bearing for the first time.
- Add a tiny smoke test that proves the generator output exists, that a generated interface is present, and that the canonical contract is reachable when the service runs.

No business endpoint behaviour is added by this change. F04 delivers the contract pipeline; subsequent capabilities populate the contract with real paths.

## Capabilities

### New Capabilities
- `contract-first-api`: An OpenAPI 3.x document is the authoritative definition of the public HTTP surface, structured as a root file with per-path/per-schema `$ref`s. The Gradle build generates Java interfaces (one per tag) and DTOs (one per schema) from that document before Java compilation, into dedicated packages separated from domain and application code. The runtime serves the same document at a stable URL, and under `dev` a Swagger UI loads from it. The error envelope is defined once and referenced from every error response.

### Modified Capabilities
None. F04 is a foundation capability introducing the contract-first pipeline; later changes (F03, F05, F06, F08) will add their own paths and schemas to the contract under this capability's rules.

## Impact

- **Code**: Adds the OpenAPI document tree under `bootstrap/src/main/resources/openapi/` (root file + `paths/` + `schemas/` subfolders) and one hand-written `OpenApiController` (or static-resource handler) in `com.bank.core.infrastructure.web` that serves the canonical document at `/v3/api-docs`. Adds a Swagger UI bean (or static dependency) gated by `@Profile("dev")` in `com.bank.core.config`.
- **Build**: Introduces the `org.openapi.generator` Gradle plugin (or equivalent — see [design.md](design.md)) applied to the `infrastructure` module. Adds a generation task that runs before `compileJava` and writes to `infrastructure/build/generated/openapi/src/main/java`. The generated source root is registered with the Java source sets so the IDE and the compiler both see it. `./gradlew clean build` regenerates from scratch.
- **Dependencies**: Adds `org.openapi.generator:openapi-generator-gradle-plugin` to the `infrastructure` module's classpath. Adds `springdoc-openapi-starter-webmvc-ui` (or equivalent) gated to the `dev` profile for the docs UI. The generated DTOs depend only on standard Jakarta validation and Jackson annotations — both already on the `infrastructure` classpath via Spring Boot starters added in F00.
- **Downstream**: Unblocks F03 (the error contract now has a single home — the OpenAPI document — and the envelope schema lives under `components.schemas`), F05 (`GET /api/v1/accounts/{accountNumber}`), F06 (`POST /api/v1/transfers`), and F08 (account opening). Each subsequent capability adds its own `paths/<path>.yaml` and `schemas/<name>.yaml` files and re-runs the build; the hand-written controllers implement the freshly generated interfaces.
- **Conventions**: Reinforces ArchUnit rule 1 (no `com.bank.core.{api,dto}` imports from `domain`) and rule 2 (none from `application`). These rules were added in F00 anticipating F04; F04 makes them real.
- **No production OpenAPI annotations** on hand-written code — the contract is the YAML, not Java annotations. The Swagger UI under `dev` reads the served YAML, never scans annotations.
