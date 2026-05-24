## Context

F00 has shipped: the `v2-sdd` branch now has a working multi-module Gradle build (`domain`, `application`, `infrastructure`, `bootstrap`), Spring Boot 3.4.x with `@EnableScheduling`/`@EnableAsync`, Flyway-managed schema, `ddl-auto=validate` everywhere, and ArchUnit rules that already ban `com.bank.core.api..` and `com.bank.core.dto..` imports from `domain` and `application`. Those packages currently do not exist — F04 creates them.

The published target spec at [openspec/specs/contract-first-api/spec.md](openspec/specs/contract-first-api/spec.md) defines six requirements: contract as static resources with `$ref`s, build-time generation, interfaces-only, package separation (`com.bank.core.api`, `com.bank.core.dto`), canonical contract served at runtime, and a single shared error envelope referenced everywhere. The manifest at [openspec/config.yaml](openspec/config.yaml) lists F04 as a `foundation` capability that unblocks F03/F05/F06/F08. The error envelope itself is F03's concern, but F04 is the change that introduces the place where that envelope is defined.

Constraints inherited from F00: Java 25 toolchain, Spring Boot 3.4.x (the F00 archive notes 3.4.5 was used due to JDK 25 bytecode), Gradle 8.14.x Kotlin DSL, generated sources must not pollute `domain` or `application`. No business endpoint exists yet on this branch — F04 must validate the pipeline with the smallest possible contract slice and let later capabilities add real paths.

## Goals / Non-Goals

**Goals:**
- A single OpenAPI 3.x root file under `bootstrap/src/main/resources/openapi/openapi.yaml`, `$ref`ing per-path files under `paths/` and per-schema files under `schemas/`, so each later capability adds its own files without touching the root beyond a single new `$ref` line.
- `./gradlew build` on a fresh checkout runs OpenAPI code generation before `compileJava` automatically, produces one Java interface per OpenAPI tag in `com.bank.core.api` and one DTO per schema in `com.bank.core.dto`, and the build succeeds with no manual generator invocation.
- Deleting `infrastructure/build/generated/openapi` and re-running `./gradlew build` regenerates and succeeds.
- Generated output is **interfaces only** — no controller bodies, no abstract base classes wired into Spring.
- A hand-written controller in `com.bank.core.infrastructure.web` serves the canonical OpenAPI document at `GET /v3/api-docs`, byte-equivalent to the file the generator consumed.
- Under the `dev` profile, a Swagger UI is reachable in a browser and loads its definition from `/v3/api-docs` (not from runtime annotation scanning).
- The error envelope schema is defined exactly once under `components.schemas` of the root document. Every `4xx`/`5xx` response in this change's contract `$ref`s it. (F03 will add the code taxonomy; F04 establishes the schema's home.)
- The F00 ArchUnit rules continue to pass — no class in `domain` or `application` imports anything from `com.bank.core.{api,dto}`.

**Non-Goals:**
- Defining the real business endpoints. F04 ships a deliberately minimal contract slice — the pipeline is the deliverable, not the API. F05/F06/F08 add real paths.
- Defining the full error code taxonomy. F03 owns the codes and the HTTP-status mapping; F04 owns the envelope schema's location and reference pattern.
- Generating Java clients, TypeScript clients, or any other non-server-side artefact.
- Auth, CORS, or rate-limiting on the docs UI — out of scope for a study branch.
- Versioning the contract beyond `/api/v1`. The path prefix is set; a v2 contract is a future concern.
- Splitting the contract across multiple OpenAPI documents (one per capability). One root document, multiple `$ref` files.

## Decisions

### Generator choice: OpenAPI Generator Gradle plugin (`org.openapi.generator`), `spring` generator, `interfaceOnly=true`, `delegatePattern=false`

`org.openapi.generator:openapi-generator-gradle-plugin` is the most widely-deployed and best-documented option for Spring Boot 3.x. Its `spring` generator with `interfaceOnly=true` produces exactly what the spec demands: one Java interface per tag with `@RequestMapping`/`@GetMapping`/etc on each method, plus DTO records or classes per schema. `useSpringBoot3=true` ensures Jakarta (not javax) imports and Spring Boot 3 idioms.

Configuration block (applied to the `infrastructure` module):

```kotlin
openApiGenerate {
  generatorName = "spring"
  inputSpec = "$rootDir/bootstrap/src/main/resources/openapi/openapi.yaml"
  outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath
  apiPackage = "com.bank.core.api"
  modelPackage = "com.bank.core.dto"
  invokerPackage = "com.bank.core.api.invoker"   // generator demands one even if unused
  configOptions = mapOf(
    "interfaceOnly" to "true",
    "useSpringBoot3" to "true",
    "useJakartaEe" to "true",
    "openApiNullable" to "false",
    "skipDefaultInterface" to "true",
    "performBeanValidation" to "true",
    "useTags" to "true"
  )
  globalProperties = mapOf("models" to "", "apis" to "", "supportingFiles" to "false")
}
tasks.named("compileJava") { dependsOn("openApiGenerate") }
sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
```

Rejected alternatives:
- **Springdoc as the source-of-truth + annotations on hand-written controllers**: this is the *opposite* of contract-first. Rejected outright; it would make the spec invalid by construction.
- **Swagger Codegen (older v2 fork)**: stale, no first-class Spring Boot 3 support, requires javax→jakarta workarounds.
- **`swagger-codegen-maven-plugin`**: Maven, project is Gradle.
- **Hand-rolled generation via a Gradle exec task calling `openapi-generator-cli`**: more moving parts than the plugin, no per-task incremental build awareness.
- **`delegatePattern=true`**: would produce both an interface and an abstract `XxxApiDelegate` for the controller to extend. Spec calls for interface-only; the delegate adds indirection F04 does not need.
- **Models-only generation** (skip apis): would deliver DTOs but force hand-writing every controller signature, defeating the "controller signature drift fails the build" scenario.

### Document layout: single root file with `$ref`s under `paths/` and `schemas/`

```
bootstrap/src/main/resources/openapi/
├── openapi.yaml                  # root: info, servers, tags, paths{}, components.schemas{}
├── paths/
│   └── api-docs.yaml             # GET /v3/api-docs (placeholder; later: accounts.yaml, transfers.yaml...)
└── schemas/
    └── error-envelope.yaml       # ErrorEnvelope { code, message, timestamp }
```

The root file's `paths` block uses external `$ref`s:

```yaml
paths:
  /v3/api-docs:
    $ref: "./paths/api-docs.yaml"
components:
  schemas:
    ErrorEnvelope:
      $ref: "./schemas/error-envelope.yaml"
```

The OpenAPI generator follows external `$ref`s natively. Diffs stay small: a new endpoint changes the root file by one line (one new `$ref`) and adds one new file under `paths/`.

Rejected: one giant `openapi.yaml`. Works but every later change rewrites a single 1k-line file and merge conflicts on every PR.

### Canonical contract URL: `GET /v3/api-docs`, hand-written controller

A hand-written `OpenApiController` under `com.bank.core.infrastructure.web` reads `classpath:/openapi/openapi.yaml` and inlines the `$ref`s into a single document at startup (using `io.swagger.parser.v3:swagger-parser` or the OpenAPI generator's bundled parser), serving it as `application/yaml` with content negotiation for `application/json`.

Why `/v3/api-docs`: it's the path Springdoc Swagger UI defaults to, so the Swagger UI bean under `dev` finds the contract with zero extra configuration.

Alternative considered: serve the root file as a static resource via `addResourceHandlers` and rely on the Swagger UI to follow `$ref`s itself. Rejected — Swagger UI's `$ref` resolution against `file://` style paths is fragile, and external consumers would each need to fetch every file separately. Inlining at the server boundary is cheap (the contract is small) and gives one URL → one document.

### Swagger UI under `dev` profile only

Add `org.springdoc:springdoc-openapi-starter-webmvc-ui` to `bootstrap` as `implementation`, but gate the auto-configuration off in `application.yaml` (`springdoc.swagger-ui.enabled=false`, `springdoc.api-docs.enabled=false`) and override to `true` in `application-dev.yaml`. Crucially, set `springdoc.swagger-ui.url=/v3/api-docs` so the UI loads from the canonical hand-served document, not from Springdoc's annotation-scanning endpoint.

Rejected: ship a static Swagger UI distribution under `bootstrap/src/main/resources/static/swagger-ui/`. Works but pulls 1MB+ into the JAR and pins the UI version; the dependency-managed approach is more honest.

### Generated package layout and module placement

Generation runs in `infrastructure` because hand-written controllers (also in `infrastructure`) implement the generated interfaces — the two sit naturally in the same compilation unit. The generated DTOs in `com.bank.core.dto` are accessible to controllers; F00's ArchUnit rules already ban `domain` and `application` from importing them.

Rejected: a fifth `api-contracts` module that owns generation and is depended on by `infrastructure`. Cleaner in theory but introduces a module purely for generated output; not worth the build-graph overhead at this scale.

### Source-set wiring and IDE behaviour

`sourceSets["main"].java.srcDir(...)` adds the generated output to the Java source set so `javac` and the IDE both see it. The `openApiGenerate` task is wired with `dependsOn` from `compileJava`. IDE imports work without manual reimport after the first `./gradlew build`.

`gradle.properties` already enables build caching from F00, so unchanged contract → no regeneration. Confirmed via the plugin's `@Input` annotations on `inputSpec` and `configOptions`.

### Error envelope: schema defined here, codes deferred to F03

F04 adds `schemas/error-envelope.yaml`:

```yaml
type: object
required: [code, message, timestamp]
properties:
  code: { type: string, description: "Stable machine-readable error code (taxonomy in F03)" }
  message: { type: string }
  timestamp: { type: string, format: date-time }
```

The single placeholder endpoint shipped in F04 references it via `$ref: "#/components/schemas/ErrorEnvelope"` on its `4xx`/`5xx` responses. F03 will tighten the spec by enumerating the codes; F04 only locks the *location* and the *referencing pattern*.

### Minimum endpoint to validate the pipeline

The contract in F04 declares exactly one operation: `GET /v3/api-docs` itself, tagged `openapi`, returning the OpenAPI document (`application/yaml` or `application/json`) with a `5xx` response referencing `ErrorEnvelope`. The hand-written `OpenApiController` implements the generated `OpenapiApi` interface.

This is deliberately self-referential — the smallest possible operation that proves the pipeline end-to-end:
- The contract has at least one tag (`openapi`) → one interface is generated.
- The contract has at least one schema (`ErrorEnvelope`) → one DTO is generated (or, since `ErrorEnvelope` is referenced in responses, the DTO definitely materialises).
- The contract has at least one error response → the `$ref` pattern is exercised.
- The endpoint at runtime serves the document → the canonical-URL requirement is testable.

When F03/F05/F06/F08 land, they replace or supersede this self-referential endpoint with real business contracts; the pipeline does not change.

### Build ordering and incrementality

`openApiGenerate` declares `inputSpec` (and the surrounding directory via a `@InputDirectory` input) and `outputDir` as `@OutputDirectory`. Gradle treats it as up-to-date when neither side has changed. `compileJava dependsOn openApiGenerate` ensures the source set is populated before compilation. `clean` removes `build/generated/openapi`; next build restores it. Acceptance check: delete the generated folder, run `./gradlew build`, observe the folder reappear.

## Risks / Trade-offs

- **OpenAPI Generator + JDK 25 bytecode** → the generator's own code runs on the build JVM (Gradle daemon, currently JDK 21 per F00). Generated **output** is plain Java compiled by the project's JDK 25 toolchain. No issue expected. Mitigation: the integration smoke test in `tasks.md` exercises a full build on a fresh checkout.
- **External `$ref` resolution at runtime** → swagger-parser is well-tested but adds startup cost. Mitigation: parse once at startup into a `String` field, serve from memory; size is small enough that this is invisible.
- **Generator drift on minor version bumps** → OpenAPI Generator occasionally changes default record-vs-class output or annotation choices between minor versions. Mitigation: pin the plugin version explicitly in `infrastructure/build.gradle.kts`; flag the pin in implementation notes; revisit when later capabilities are added.
- **Swagger UI loaded under `dev` profile interacts with the H2 console** → both are dev-only affordances under different paths (`/h2-console`, `/swagger-ui.html`). No conflict expected; ArchUnit's profile-gated tests from F00 cover the `default`-profile-must-not-expose case for H2 console — F04 adds the equivalent for Swagger UI.
- **`/v3/api-docs` collides with Springdoc's default endpoint** → Springdoc's own `/v3/api-docs` is disabled via `springdoc.api-docs.enabled=false` in `application.yaml`. The hand-written controller owns the path. Verified by the smoke test asserting the served body equals the contract file.
- **Contract drift across capability branches** → F03/F05/F06/F08 will each `$ref` new files in. Risk: two capabilities edit the root `openapi.yaml` `paths` block and conflict. Mitigation: per-path files keep the root edit to one line per capability; merge conflicts on a single line are trivial. Documented in this design's "Document layout" section.
- **Generated DTO style (records vs classes) coupling F03/F05/F06 to a choice made here** → the generator's default for `useSpringBoot3=true` + recent versions emits classes with builders, not records. Mitigation: leave it on the default. If a later spec wants records, a `configOption` flip changes all DTOs at once.

## Migration Plan

- **Deploy**: Land via PR. Reviewer runs `./gradlew build` from a clean checkout — generation runs, build succeeds, smoke tests pass. `./gradlew :bootstrap:bootRun` starts the service; `curl http://localhost:8080/v3/api-docs` returns the OpenAPI document; under `--spring.profiles.active=dev`, `GET /swagger-ui.html` returns 200 and the UI loads the document.
- **Rollback**: This change touches only new files (the `openapi/` resource tree, generator wiring in `infrastructure/build.gradle.kts`, the `OpenApiController`, the dev-profile UI config) plus one new `application.yaml` block. `git revert` cleanly removes the lot. The F00 skeleton is untouched.
- **Forward path**: F03 adds the error-code enum under `components.schemas.ErrorCode` and updates `error-envelope.yaml` to reference it. F05/F06/F08 each add `paths/<path>.yaml` + `schemas/<name>.yaml` files and one `$ref` line in the root document. No regeneration of F04's pipeline is needed.

## Open Questions

- **Generator plugin version pin**: the latest stable line is 7.x. Implementation will pick the newest 7.x compatible with the Gradle 8.14.x daemon JVM (JDK 21 per F00) and the project's JDK 25 toolchain. Pin recorded in `tasks.md` implementation notes after verification.
- **Validation depth on DTOs**: `performBeanValidation=true` emits `@Valid`/`@NotNull` etc. Acceptable for F04. F03 may want to tighten or relax this when concrete endpoint contracts arrive.
- **`@RestController` vs `@Controller` on the generated interfaces**: the `spring` generator with `interfaceOnly=true` emits neither — the interface carries `@RequestMapping`, and the hand-written class adds `@RestController` itself. Confirmed acceptable; no decision needed.
- **Whether to mirror the contract path under `/openapi.yaml` as an alias**: Springdoc convention is `/v3/api-docs`. Some external tooling expects `/openapi.yaml`. Deferring — can be added as a one-line controller mapping if a downstream capability needs it.
