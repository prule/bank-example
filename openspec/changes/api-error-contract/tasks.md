## 1. Tighten the OpenAPI error envelope schema

- [ ] 1.1 Edit `bootstrap/src/main/resources/openapi/schemas/error-envelope.yaml`: change `code.type` from a free `string` to `type: string` plus `enum: [INSUFFICIENT_FUNDS, ACCOUNT_INACTIVE, RESOURCE_NOT_FOUND, BAD_REQUEST_PAYLOAD, INTERNAL_SERVER_ERROR]`. Replace the top-level `description` with a description that documents the code → HTTP mapping table inline (mirrors the published spec).
- [ ] 1.2 Update the `code.example` value to a canonical taxonomy value (e.g. `BAD_REQUEST_PAYLOAD`) — the prior placeholder `VALIDATION_FAILED` is no longer valid under the enum.
- [ ] 1.3 Run `./gradlew :infrastructure:openApiGenerate --rerun-tasks` and confirm `infrastructure/build/generated/openapi/src/main/java/com/bank/core/dto/ErrorEnvelope.java` now exposes a public nested `CodeEnum` with the five constants and a `fromValue(String)` factory.

## 2. Add Spring MVC configuration knobs

- [ ] 2.1 In `bootstrap/src/main/resources/application.yaml`, add under `spring`: `mvc.throw-exception-if-no-handler-found: true` and `web.resources.add-mappings: false` so `NoHandlerFoundException` is reachable and Spring's static-resource handler does not silently swallow unknown paths.
- [ ] 2.2 Verify the dev-profile Swagger UI still loads after this change — Springdoc registers its own resource handler, so the F04 `SwaggerUiDevProfileTest` should still pass. If it does not, narrow `add-mappings: false` to a `WebMvcConfigurer` that excludes `/swagger-ui/**` and `/internal/springdoc-api-docs/**`.

## 3. Implement the global exception handler

- [ ] 3.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/web/error/GlobalExceptionHandler.java` annotated `@RestControllerAdvice`. Inject (or instantiate) a private SLF4J `Logger`.
- [ ] 3.2 Add a small private helper `private ErrorEnvelope envelope(ErrorEnvelope.CodeEnum code, String message)` that constructs the DTO with `OffsetDateTime.now(ZoneOffset.UTC)` and returns it.
- [ ] 3.3 Add `@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})` returning `ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope(BAD_REQUEST_PAYLOAD, …))`. The message names up to three offending fields drawn from the binding result / constraint violations.
- [ ] 3.4 Add `@ExceptionHandler(HttpMessageNotReadableException.class)` returning 400 `BAD_REQUEST_PAYLOAD` with the static message `"Malformed request body."` Do not include the raw cause text.
- [ ] 3.5 Add `@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})` returning 404 `RESOURCE_NOT_FOUND` with message `"No handler found for " + request.getMethod() + " " + request.getRequestURI()`.
- [ ] 3.6 Add `@ExceptionHandler(Exception.class)` returning 500 `INTERNAL_SERVER_ERROR` with the static message `"An unexpected error occurred. Please contact support."` Log the full exception at `ERROR` with the request method and path.
- [ ] 3.7 Log validation/parse/not-found handlers at `INFO` (expected client error) — not `WARN` and not `ERROR`.
- [ ] 3.8 Ensure all handlers return `ResponseEntity<ErrorEnvelope>` so Jackson serialises with exactly the three documented properties — no Spring-added fields.

## 4. Tests

- [ ] 4.1 Create `bootstrap/src/test/java/com/bank/core/web/error/ErrorTestController.java` as a `@RestController` mapped under `/internal/test-errors`, with one endpoint per failure type: `GET /validation` (declares a required `@RequestParam`, omit it to trigger validation), `POST /parse` (accepts `@Valid` body, send malformed JSON), `GET /boom` (throws `new RuntimeException("internal probe failure")`).
- [ ] 4.2 Wrap that controller in a `@TestConfiguration` annotated class so it only loads inside the F03 tests, never on production classpath.
- [ ] 4.3 Create `bootstrap/src/test/java/com/bank/core/web/error/GlobalExceptionHandlerTest.java` annotated `@SpringBootTest(webEnvironment = RANDOM_PORT)` importing the `@TestConfiguration` above. For each endpoint: hit it with `TestRestTemplate`, assert status code, assert envelope has exactly three keys (`code`, `message`, `timestamp`), assert `code` matches the expected enum value, assert `timestamp` parses as `OffsetDateTime`, assert `message` is non-empty.
- [ ] 4.4 In the same test class, add a leakage-assertion helper: assert the `message` field of the 500 response does NOT contain any of `java.`, `org.springframework.`, `com.bank.core.`, `at `, `SELECT`, `INSERT`, `UPDATE`, `DELETE`.
- [ ] 4.5 Create `bootstrap/src/test/java/com/bank/core/web/error/NotFoundHandlerTest.java` annotated `@SpringBootTest(webEnvironment = RANDOM_PORT)` (no test controller). Assert `GET /no/such/path` returns 404 with `code = RESOURCE_NOT_FOUND` and the documented body shape. Spot-check the same with `HEAD /no/such/path` and `OPTIONS /no/such/path` — if either bypasses the advice, fix the handler (e.g. expand the catch list) before completing this task.
- [ ] 4.6 Add a test that asserts the regenerated `com.bank.core.dto.ErrorEnvelope.CodeEnum` exposes exactly the five canonical constants and rejects unknown values via `fromValue`.

## 5. Verification

- [ ] 5.1 Run `./gradlew clean build` — generator regenerates `ErrorEnvelope` with the typed enum, handler compiles, all new tests pass, the F04 `OpenApiContractTest` and `SwaggerUiDevProfileTest` still pass.
- [ ] 5.2 Run the service with `./gradlew :bootstrap:bootRun` and `curl -i http://localhost:8080/no/such/path` — confirm 404 JSON with `{"code":"RESOURCE_NOT_FOUND",…}`.
- [ ] 5.3 Run the service and `curl -i -H 'Content-Type: application/json' -d '{not json' http://localhost:8080/v3/api-docs` — confirm 400 JSON with `{"code":"BAD_REQUEST_PAYLOAD",…}`. (The `/v3/api-docs` endpoint is GET, so the request will be rejected before parsing; pick any POST endpoint when F05/F06 land — for now skip this manual check if no POST endpoint exists.)
- [ ] 5.4 Grep the production code for any string literal matching the canonical taxonomy outside `GlobalExceptionHandler` — there must be no such literal; all references go through `ErrorEnvelope.CodeEnum`.
- [ ] 5.5 Confirm `bootstrap/src/main/resources/openapi/openapi.yaml` still serves at runtime via `GET /v3/api-docs` and that the served `components.schemas.ErrorEnvelope.properties.code.enum` array has all five values, in the canonical order.
- [ ] 5.6 Confirm the F00 ArchUnit boundary tests still pass — the handler imports `com.bank.core.dto.ErrorEnvelope` (allowed in `infrastructure`) and does not touch `domain` or `application`.

## 6. Forward-compatibility hooks

- [ ] 6.1 Add a class-level Javadoc comment to `GlobalExceptionHandler` listing the domain exceptions that future capabilities will add: `InsufficientFundsException` (F01/F06 → 400 `INSUFFICIENT_FUNDS`), `AccountInactiveException` (F01/F06 → 400 `ACCOUNT_INACTIVE`), and `ResourceNotFoundException` (F05 → 404 `RESOURCE_NOT_FOUND`). One short paragraph so the next contributor knows exactly where to add a new `@ExceptionHandler` method.
- [ ] 6.2 Do NOT add `@ExceptionHandler` stubs for those exception types. They do not exist on this branch yet and an empty stub referencing a non-existent class breaks compilation.

## 7. Hygiene

- [ ] 7.1 If any deviation from this design is necessary during implementation (e.g. Springdoc's static-resource handler is incompatible with `add-mappings: false`), record it in an "Implementation notes / deviations from design" section appended to this `tasks.md` file (mirror the F00/F04 archives' pattern).
- [ ] 7.2 Confirm the `ReadMe.md` and `INTRODUCTION.md` describing the contract-first concept do not need an edit — F03 strengthens the same idea without changing the user-facing description.

## Implementation notes / deviations from design

<!-- Fill in during /opsx:apply. -->
