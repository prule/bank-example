## 1. OpenAPI document tree

- [ ] 1.1 Create directory `bootstrap/src/main/resources/openapi/` with `paths/` and `schemas/` subfolders.
- [ ] 1.2 Create `bootstrap/src/main/resources/openapi/openapi.yaml` as the root document: declare `openapi: 3.0.3`, `info` (title `bank-core`, version `0.1.0`, description referencing the project manifest), one `servers` entry pointing at `http://localhost:8080`, an empty `tags` array (entries added as paths are added), an empty `paths` map awaiting `$ref`s, and `components.schemas` containing exactly one entry `ErrorEnvelope` that `$ref`s `./schemas/error-envelope.yaml`.
- [ ] 1.3 Create `bootstrap/src/main/resources/openapi/schemas/error-envelope.yaml` with `type: object`, `required: [code, message, timestamp]`, properties `code` (string, description "stable machine-readable error code; taxonomy defined in F03"), `message` (string), `timestamp` (string, format date-time). Mark the schema with a top-level `description` noting F03 will tighten the `code` property to an enum.
- [ ] 1.4 Create `bootstrap/src/main/resources/openapi/paths/api-docs.yaml` describing `get: { tags: [openapi], operationId: getOpenApiDocument, summary: "Fetch the canonical OpenAPI document", responses: { '200': { description: "OpenAPI document", content: { application/yaml: { schema: { type: string } }, application/json: { schema: { type: object } } } }, '5XX': { description: "Server error", content: { application/json: { schema: { $ref: '#/components/schemas/ErrorEnvelope' } } } } } }`.
- [ ] 1.5 Add a `tags:` entry in the root `openapi.yaml` for `openapi` (description "Operations on the API contract itself"), and a `paths` entry mapping `/v3/api-docs` to `$ref: "./paths/api-docs.yaml"`.
- [ ] 1.6 Validate the document compiles: run `./gradlew openApiGenerate` (after task 2 completes) and confirm no parser errors.

## 2. Gradle generator wiring (infrastructure module)

- [ ] 2.1 Add the OpenAPI Generator Gradle plugin to `infrastructure/build.gradle.kts` via `plugins { id("org.openapi.generator") version "<pinned 7.x>" }`. Pin to the most recent 7.x compatible with the Gradle 8.14.x daemon (JDK 21) and the project's JDK 25 toolchain — verify by running the build and record the chosen version in implementation notes.
- [ ] 2.2 In `infrastructure/build.gradle.kts`, configure the `openApiGenerate` task: `generatorName = "spring"`, `inputSpec = "$rootDir/bootstrap/src/main/resources/openapi/openapi.yaml"`, `outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath`, `apiPackage = "com.bank.core.api"`, `modelPackage = "com.bank.core.dto"`, `invokerPackage = "com.bank.core.api.invoker"`, `configOptions = mapOf("interfaceOnly" to "true", "useSpringBoot3" to "true", "useJakartaEe" to "true", "openApiNullable" to "false", "skipDefaultInterface" to "true", "performBeanValidation" to "true", "useTags" to "true")`, `globalProperties = mapOf("supportingFiles" to "false")`.
- [ ] 2.3 Wire `tasks.named("compileJava") { dependsOn("openApiGenerate") }` and `sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))` so generated output joins the compilation source set.
- [ ] 2.4 Ensure `infrastructure` has the Jakarta validation and Jackson annotations on its compile classpath via the existing Spring Boot starters from F00. Add `implementation("io.swagger.core.v3:swagger-annotations")` if the generator emits Swagger annotations on the generated interfaces.
- [ ] 2.5 Add `infrastructure/build/generated/` to `.gitignore` (or confirm the existing `build/` exclusion already covers it).
- [ ] 2.6 Run `./gradlew :infrastructure:openApiGenerate` and confirm `infrastructure/build/generated/openapi/src/main/java/com/bank/core/api/OpenapiApi.java` and `infrastructure/build/generated/openapi/src/main/java/com/bank/core/dto/ErrorEnvelope.java` are produced.

## 3. Canonical contract controller

- [ ] 3.1 Add `implementation("io.swagger.parser.v3:swagger-parser:<latest>")` to `bootstrap/build.gradle.kts` for `$ref` resolution at startup.
- [ ] 3.2 Create `bootstrap/src/main/java/com/bank/core/infrastructure/web/OpenApiController.java` (or in the `infrastructure` module under `com.bank.core.infrastructure.web` — placement decided by F00's package layout; controllers belong in `infrastructure`). The class is annotated `@RestController` and implements `com.bank.core.api.OpenapiApi`.
- [ ] 3.3 In the controller's constructor, load `classpath:/openapi/openapi.yaml` via the resource loader, parse it through `OpenAPIV3Parser` with `ParseOptions().setResolve(true).setResolveFully(true)` to inline all `$ref`s, and cache the result as both YAML and JSON strings on the bean.
- [ ] 3.4 Implement the generated `getOpenApiDocument()` method to return the cached document, with content negotiation: `application/yaml` (or `application/x-yaml`) when the `Accept` header requests YAML; `application/json` otherwise. Use a `ResponseEntity<String>` with the appropriate `Content-Type`.
- [ ] 3.5 Confirm by running the service and `curl -i http://localhost:8080/v3/api-docs` returns 200 with a non-empty body containing `openapi: 3.0.3` (YAML) or `"openapi":"3.0.3"` (JSON).

## 4. Swagger UI under dev profile

- [ ] 4.1 Add `implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:<latest 2.x>")` to `bootstrap/build.gradle.kts`.
- [ ] 4.2 In `bootstrap/src/main/resources/application.yaml`, add `springdoc: { api-docs: { enabled: false }, swagger-ui: { enabled: false } }` so the default profile does not expose the UI or Springdoc's annotation-scanned `/v3/api-docs`.
- [ ] 4.3 In `bootstrap/src/main/resources/application-dev.yaml`, override to `springdoc: { api-docs: { enabled: false }, swagger-ui: { enabled: true, url: /v3/api-docs, path: /swagger-ui.html } }`. Note: `api-docs.enabled` stays `false` because the hand-written controller owns `/v3/api-docs`; the UI's `url` points at the hand-written endpoint.
- [ ] 4.4 Start the service with `--spring.profiles.active=dev` and confirm `GET /swagger-ui.html` returns 200 and the page renders the contract.
- [ ] 4.5 Start the service with the `default` profile and confirm `GET /swagger-ui.html` returns 404 while `GET /v3/api-docs` still returns 200.

## 5. Smoke tests

- [ ] 5.1 Add a JUnit 5 integration test in `infrastructure/src/test/java/com/bank/core/infrastructure/web/OpenApiContractTest.java` annotated `@SpringBootTest(webEnvironment = RANDOM_PORT)` that asserts `GET /v3/api-docs` returns 200 and the body contains `openapi:` and `ErrorEnvelope`.
- [ ] 5.2 Add a test that loads the canonical document via the classpath resource AND via the controller, parses both with `OpenAPIV3Parser`, and asserts the resolved `paths` and `components.schemas` maps are equal (content-equivalent).
- [ ] 5.3 Add a test under `bootstrap/src/test/java/com/bank/core/architecture/` that fails if any class under `com.bank.core.infrastructure.web` is wired to a Spring-generated `*ApiDelegate` (i.e. assert there is no class named matching `*ApiDelegate` on the production classpath).
- [ ] 5.4 Add a test that loads the `dev` Spring context (`@ActiveProfiles("dev")` on a separate test class) and asserts `GET /swagger-ui.html` returns 200. Keep this in `bootstrap/src/test/java/com/bank/core/web/` to mirror the F00 profile-gating pattern.
- [ ] 5.5 Confirm the existing F00 ArchUnit boundary tests still pass — no class under `com.bank.core.domain` or `com.bank.core.application` imports `com.bank.core.api..` or `com.bank.core.dto..`.

## 6. Verification

- [ ] 6.1 Run `./gradlew clean build` from a fresh checkout — generation runs as a `compileJava` dependency, all modules compile, all tests pass, exit status `0`.
- [ ] 6.2 Delete `infrastructure/build/generated/openapi/` and re-run `./gradlew build` — confirm the folder is regenerated and the build still succeeds.
- [ ] 6.3 Edit `schemas/error-envelope.yaml` to add a temporary `details: { type: string }` property; run `./gradlew build`; confirm the generated DTO now carries the new field; revert.
- [ ] 6.4 Temporarily change the return type in the generated `OpenapiApi` (by editing the contract path file to declare a different schema) and confirm the hand-written `OpenApiController` fails compilation; revert.
- [ ] 6.5 Temporarily add `import com.bank.core.dto.ErrorEnvelope;` to a class under `com.bank.core.domain`; run `./gradlew :bootstrap:test`; confirm the F00 ArchUnit boundary test fails; revert.
- [ ] 6.6 Run `./gradlew :bootstrap:bootRun &` then `curl -H 'Accept: application/yaml' http://localhost:8080/v3/api-docs` and `curl -H 'Accept: application/json' http://localhost:8080/v3/api-docs`; confirm both succeed with appropriate `Content-Type` and the expected payload. Stop the service.
- [ ] 6.7 Grep the contract sources for inline error schemas: `grep -r "type: object" bootstrap/src/main/resources/openapi/paths/` should not return any path-file response defining its own error envelope; all error responses must `$ref` `ErrorEnvelope`.

## 7. Documentation and hygiene

- [ ] 7.1 Update `run.sh`'s `swagger` target (added as a placeholder in F00) to `:bootstrap:bootRun --args='--spring.profiles.active=dev'` and echo the URL `http://localhost:8080/swagger-ui.html`.
- [ ] 7.2 If the OpenAPI generator plugin or Spring Boot version chosen here differs from `openspec/config.yaml`, flag the deviation in an "Implementation notes / deviations from design" section at the bottom of this file (mirror the F00 archive's pattern). Do not modify `openspec/config.yaml` directly in this change.
- [ ] 7.3 Skim `INTRODUCTION.md` and `ReadMe.md` — if either describes how to view the API docs, add a line referencing `http://localhost:8080/swagger-ui.html` under the `dev` profile. If neither does, take no action.

## Implementation notes / deviations from design

<!-- Fill in during /opsx:apply with any deviations from this design, mirroring the F00 archive's pattern. -->
