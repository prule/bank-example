## 1. OpenAPI document tree

- [x] 1.1 Create directory `bootstrap/src/main/resources/openapi/` with `paths/` and `schemas/` subfolders.
- [x] 1.2 Create `bootstrap/src/main/resources/openapi/openapi.yaml` as the root document: declare `openapi: 3.0.3`, `info` (title `bank-core`, version `0.1.0`, description referencing the project manifest), one `servers` entry pointing at `http://localhost:8080`, an empty `tags` array (entries added as paths are added), an empty `paths` map awaiting `$ref`s, and `components.schemas` containing exactly one entry `ErrorEnvelope` that `$ref`s `./schemas/error-envelope.yaml`.
- [x] 1.3 Create `bootstrap/src/main/resources/openapi/schemas/error-envelope.yaml` with `type: object`, `required: [code, message, timestamp]`, properties `code` (string, description "stable machine-readable error code; taxonomy defined in F03"), `message` (string), `timestamp` (string, format date-time). Mark the schema with a top-level `description` noting F03 will tighten the `code` property to an enum.
- [x] 1.4 Create `bootstrap/src/main/resources/openapi/paths/api-docs.yaml` describing `get: { tags: [openapi], operationId: getOpenApiDocument, summary: "Fetch the canonical OpenAPI document", responses: { '200': { description: "OpenAPI document", content: { application/yaml: { schema: { type: string } }, application/json: { schema: { type: object } } } }, '5XX': { description: "Server error", content: { application/json: { schema: { $ref: '#/components/schemas/ErrorEnvelope' } } } } } }`.
- [x] 1.5 Add a `tags:` entry in the root `openapi.yaml` for `openapi` (description "Operations on the API contract itself"), and a `paths` entry mapping `/v3/api-docs` to `$ref: "./paths/api-docs.yaml"`.
- [x] 1.6 Validate the document compiles: run `./gradlew openApiGenerate` (after task 2 completes) and confirm no parser errors.

## 2. Gradle generator wiring (infrastructure module)

- [x] 2.1 Add the OpenAPI Generator Gradle plugin to `infrastructure/build.gradle.kts` via `plugins { id("org.openapi.generator") version "<pinned 7.x>" }`. Pinned to `7.10.0`.
- [x] 2.2 In `infrastructure/build.gradle.kts`, configure the `openApiGenerate` task with `interfaceOnly`, `useSpringBoot3`, `useJakartaEe`, `openApiNullable=false`, `skipDefaultInterface`, `performBeanValidation`, `useTags`, plus `documentationProvider=none` and `annotationLibrary=none` (added — avoids dragging swagger-annotations onto the classpath while keeping the spec's "interfaces only" intent).
- [x] 2.3 Wire `tasks.named("compileJava") { dependsOn("openApiGenerate") }` and `sourceSets["main"].java.srcDir(...)` so generated output joins the compilation source set.
- [x] 2.4 No swagger-annotations dep needed because `annotationLibrary=none` is set. Validation imports (`jakarta.validation.*`) and Jackson come transitively from the Spring Boot starters from F00.
- [x] 2.5 `build/` is already covered by the repo `.gitignore` from F00; no new entry needed.
- [x] 2.6 `./gradlew :infrastructure:openApiGenerate` produces `OpenapiApi.java`, `ErrorEnvelope.java`, and `ApiUtil.java`. `./gradlew :infrastructure:compileJava` succeeds.

## 3. Canonical contract controller

- [x] 3.1 Added `implementation("io.swagger.parser.v3:swagger-parser:2.1.22")` to `infrastructure/build.gradle.kts` (not `bootstrap` as design suggested — controller is in infrastructure per F00's package layout, so swagger-parser must be on infrastructure's compile classpath).
- [x] 3.2 Created `infrastructure/src/main/java/com/bank/core/infrastructure/web/OpenApiController.java` annotated `@RestController` implementing `com.bank.core.api.OpenapiApi`.
- [x] 3.3 Constructor loads `classpath:/openapi/openapi.yaml` via the classloader URL, parses through `OpenAPIV3Parser` with `setResolve(true).setResolveFully(true)`, and caches both `Yaml.pretty(openAPI)` and `Json.pretty(openAPI)`.
- [x] 3.4 `getOpenApiDocument()` returns `ResponseEntity<String>` with `Content-Type: application/yaml` when the Accept header prefers YAML, JSON otherwise.
- [x] 3.5 `curl -H 'Accept: application/yaml' http://localhost:8080/v3/api-docs` returns 200 with `openapi: 3.0.3`; `application/json` returns 200 with `"openapi":"3.0.3"`. Verified.

## 4. Swagger UI under dev profile

- [x] 4.1 Added `implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")` to `bootstrap/build.gradle.kts`.
- [x] 4.2 `application.yaml` sets `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` so default/test profiles do not expose the UI.
- [x] 4.3 `application-dev.yaml` enables both, but moves Springdoc's annotation-scanned doc to `/internal/springdoc-api-docs` so it does not collide with the hand-written `/v3/api-docs`. The UI's `url` points at the hand-written endpoint and `disable-swagger-default-url=true` stops the UI from offering the demo Petstore URL. **Deviation from design**: Springdoc 2.x couples its UI auto-config to `springdoc.api-docs.enabled` — disabling api-docs disables the UI too. Leaving api-docs enabled but on a non-collision path is the working pattern. Recorded in deviations.
- [x] 4.4 Under `--spring.profiles.active=dev`: `GET /swagger-ui.html` returns 302 → `/swagger-ui/index.html` returns 200; `swagger-initializer.js` loads `configUrl: /internal/springdoc-api-docs/swagger-config` which returns `{ url: "/v3/api-docs" }`; the UI then loads the hand-written contract. Verified.
- [x] 4.5 Under the default profile: `GET /swagger-ui.html` and `/swagger-ui/index.html` both return 404; `GET /v3/api-docs` still returns 200. Verified.

## 5. Smoke tests

- [x] 5.1 Created `bootstrap/src/test/java/com/bank/core/web/OpenApiContractTest.java` (moved from `infrastructure/` because infrastructure has no `@SpringBootApplication`; bootstrap is where Spring context tests live). Asserts YAML and JSON content negotiation each return 200 with the expected body markers. Added `testImplementation("io.swagger.parser.v3:swagger-parser:2.1.22")` to `bootstrap/build.gradle.kts` so the comparison test has the parser on its classpath.
- [x] 5.2 The same `OpenApiContractTest.servedDocumentMatchesCanonicalClasspathContract` test loads the classpath contract via `OpenAPIV3Parser`, loads the controller response via the same parser, and asserts the `paths` and `components.schemas` key sets are equal.
- [x] 5.3 Created `bootstrap/src/test/java/com/bank/core/architecture/NoApiDelegateTest.java` — fails the build if any class on the production classpath has a simple name ending in `ApiDelegate`.
- [x] 5.4 Created `bootstrap/src/test/java/com/bank/core/web/SwaggerUiDevProfileTest.java` annotated `@ActiveProfiles("dev")`, asserting `/swagger-ui.html` redirects/renders, `/swagger-ui/index.html` returns 200 with `Swagger UI` in the body, and `/internal/springdoc-api-docs/swagger-config` returns the JSON pointing at `/v3/api-docs`.
- [x] 5.5 `./gradlew :bootstrap:test` passes with the new tests and the existing F00 `ModuleBoundaryTest` — no boundary regression.

## 6. Verification

- [x] 6.1 `./gradlew clean build` succeeds with all modules compiling and all tests passing.
- [x] 6.2 Deleted `infrastructure/build/generated/openapi/`, re-ran `./gradlew build` — folder regenerated, build green.
- [x] 6.3 Added temporary `details: { type: string }` to `error-envelope.yaml`, re-ran generation, confirmed `ErrorEnvelope.java` contains `private String details`. Reverted.
- [x] 6.4 Added a temporary required query parameter `drift` to `paths/api-docs.yaml`, re-ran `./gradlew :infrastructure:compileJava`. Compilation failed at `OpenApiController is not abstract and does not override abstract method getOpenApiDocument(...) in OpenapiApi`. Reverted.
- [x] 6.5 Placed a class declaring `package com.bank.core.domain;` (physically under `infrastructure/src/main/java/com/bank/core/domain/`, since domain's Gradle module has no access to DTOs and would fail compilation before ArchUnit ran — same workaround pattern F00 used for `@Entity` placement testing) that imported `com.bank.core.dto.ErrorEnvelope`. `./gradlew :bootstrap:test --tests ModuleBoundaryTest` failed on `domainHasNoFrameworkDependencies`. Reverted.
- [x] 6.6 Under default profile: `curl -H 'Accept: application/yaml' …/v3/api-docs` returns 200 with `Content-Type: application/yaml`; `Accept: application/json` returns 200 with `Content-Type: application/json`. No Accept header defaults to YAML (compatible with `*/*` matching the first `produces` entry — semantically acceptable; spec only requires "per Accept negotiation").
- [x] 6.7 `grep -r "type: object" bootstrap/src/main/resources/openapi/paths/` matches only the `200` response body of `api-docs.yaml` (legitimately "any JSON object" since the OpenAPI document is the payload). The `5XX` response correctly `$ref`s `error-envelope.yaml`. No inline error schemas.

## 7. Documentation and hygiene

- [x] 7.1 `run.sh`'s `swagger` target now `exec`s `:bootstrap:bootRun --args='--spring.profiles.active=dev'` and prints both the Swagger UI URL and the canonical contract URL.
- [x] 7.2 Deviations recorded below.
- [x] 7.3 Skimmed both `INTRODUCTION.md` and `ReadMe.md` — both describe the contract-first concept but neither explains how to view the docs UI. Per the task's "if neither does, take no action" guidance: no edits.

## Implementation notes / deviations from design

- **OpenAPI Generator plugin version**: pinned to `7.10.0`. The `openspec/config.yaml` manifest does not pin a version. 7.10.0 builds cleanly under the Gradle 8.14.x daemon (JDK 21) from F00 and produces sources accepted by the project's JDK 25 javac. No deviation from the manifest, just a concrete pin worth recording.
- **Generator config — added `documentationProvider=none` and `annotationLibrary=none`**: design.md listed only the core `configOptions`. These two extras suppress emission of `@Operation`/`@Schema` annotations onto generated interfaces and DTOs, keeping the generated output dependency-free except for Jakarta validation and Jackson (both already on the Spring Boot starter classpath). Without these flags the generator would force `io.swagger.core.v3:swagger-annotations` onto the runtime classpath for no functional benefit — the contract is the YAML, not the annotations.
- **`globalProperties = mapOf("supportingFiles" to "false")` does not work as design described**: setting that key alone causes the generator to suppress *all* output. Switched to the plugin's typed Boolean inputs (`generateApiTests=false`, `generateModelTests=false`, `generateApiDocumentation=false`, `generateModelDocumentation=false`) and dropped the `globalProperties` map.
- **swagger-parser dependency lives in `infrastructure`, not `bootstrap`**: design.md task 3.1 said to add it to `bootstrap/build.gradle.kts`. The controller lives in `infrastructure` per F00's package layout, so swagger-parser must be on infrastructure's compile classpath. Bootstrap also gets it transitively at runtime. Bootstrap's test classpath was given a direct `testImplementation` of swagger-parser so the integration tests can parse the contract.
- **Integration tests live in `bootstrap/src/test/`, not `infrastructure/src/test/`**: design.md tasks 5.1/5.2 suggested putting `OpenApiContractTest` under `infrastructure/`. Infrastructure has no `@SpringBootApplication`, so `@SpringBootTest` cannot stand up a context there. Tests moved to `bootstrap/src/test/java/com/bank/core/web/`, which already hosts the F00 profile-gating test pattern.
- **Springdoc cannot have `springdoc.api-docs.enabled=false` and serve Swagger UI**: design.md task 4.3 specified `api-docs.enabled=false` under the `dev` profile (rationale: the hand-written controller owns `/v3/api-docs`). In Springdoc 2.8.0 the entire auto-configuration is gated on `api-docs.enabled` — disabling it turns off the UI too. Working pattern: leave `api-docs.enabled=true` under `dev` and move Springdoc's annotation-scanned endpoint off the canonical path via `springdoc.api-docs.path=/internal/springdoc-api-docs`. The UI continues to use `/internal/springdoc-api-docs/swagger-config` to discover its config, that config tells the UI to fetch `/v3/api-docs` (the hand-written endpoint), and the contract loads from the canonical source. Default and test profiles still have `api-docs.enabled=false` because Swagger UI is also off there.
- **6.5 drift probe location**: domain's Gradle module has no compile-time access to `com.bank.core.dto.ErrorEnvelope`, so the natural "add an import to a domain class" demonstration fails at javac before ArchUnit runs. Followed F00's documented workaround: placed a class declaring `package com.bank.core.domain;` physically under `infrastructure/src/main/java/com/bank/core/domain/` so it could see the DTO; ArchUnit (which reads packages from bytecode) flagged it as expected.
- **No `infrastructure/build/generated/` `.gitignore` entry added**: the root `.gitignore` already excludes `build/` for every module.
