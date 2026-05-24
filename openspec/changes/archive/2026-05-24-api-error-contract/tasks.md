## 1. Tighten the OpenAPI error envelope schema

- [x] 1.1 `error-envelope.yaml` now declares `code.enum` with the five canonical values. **Deviation**: the design proposed a markdown table inside the `description`. swagger-parser's snake-yaml safe-check rejected the table syntax (`SnakeException: Exception safe-checking yaml content`). Description compressed to a single sentence referencing the api-error-contract capability for the table; the table itself lives in the published spec.
- [x] 1.2 `code.example` updated to `BAD_REQUEST_PAYLOAD`.
- [x] 1.3 Regenerated: `ErrorEnvelope.CodeEnum` exposes all five constants with `fromValue(String)`.

## 2. Add Spring MVC configuration knobs

- [x] 2.1 Both knobs added to `application.yaml` under `spring.mvc.throw-exception-if-no-handler-found` and `spring.web.resources.add-mappings`.
- [x] 2.2 F04 `SwaggerUiDevProfileTest` still passes (Springdoc's own resource handler remains active under dev). No narrowing needed.

## 3. Implement the global exception handler

- [x] 3.1 Created `infrastructure/src/main/java/com/bank/core/infrastructure/web/error/GlobalExceptionHandler.java` with a static SLF4J `Logger`.
- [x] 3.2 Private `envelope(CodeEnum, String)` helper sets timestamp to `OffsetDateTime.now(ZoneOffset.UTC)`.
- [x] 3.3 Validation handler covers `MethodArgumentNotValidException`, `BindException`, `ConstraintViolationException`, plus **deviation**: `MissingServletRequestParameterException` and `MethodArgumentTypeMismatchException` (added after a test failed — missing `@RequestParam` raises `MissingServletRequestParameterException`, not `MethodArgumentNotValidException`). Message names up to three offending fields.
- [x] 3.4 Parse handler returns static `"Malformed request body."`
- [x] 3.5 Not-found handler covers `NoHandlerFoundException` and `NoResourceFoundException`, message includes method + URI.
- [x] 3.6 Catch-all `Exception.class` returns static 500 message, logs at ERROR with request method/path and full exception.
- [x] 3.7 Validation/parse/not-found log at INFO; catch-all at ERROR.
- [x] 3.8 All handlers return `ResponseEntity<ErrorEnvelope>`; Jackson serialises with exactly three properties.

## 4. Tests

- [x] 4.1 `ErrorTestController` mounted under `/internal/test-errors` with three failure-triggering endpoints.
- [x] 4.2 `ErrorTestControllerConfig` (`@TestConfiguration`) registers it only for F03 tests.
- [x] 4.3 `GlobalExceptionHandlerTest` imports the test config, asserts status, exact-three-key shape, code value, parseable timestamp, non-blank message for each failure type.
- [x] 4.4 `assertNoLeakage` helper checks the 500 body against the banned-substring list (`java.`, `org.springframework.`, `com.bank.core.`, `at `, `SELECT`, `INSERT`, `UPDATE`, `DELETE`).
- [x] 4.5 `NotFoundHandlerTest` asserts unknown GET and OPTIONS paths return canonical 404 envelopes.
- [x] 4.6 `ErrorEnvelopeCodeEnumTest` asserts the generated enum exposes exactly the five canonical constants and `fromValue` rejects unknowns.

## 5. Verification

- [x] 5.1 `./gradlew clean build` green; all tests including F04 `OpenApiContractTest`, `SwaggerUiDevProfileTest`, `NoApiDelegateTest`, and F00 `ModuleBoundaryTest` pass.
- [x] 5.2 `curl -i http://localhost:8080/no/such/path` returns 404 with `{"code":"RESOURCE_NOT_FOUND","message":"No handler found for GET /no/such/path","timestamp":"..."}`.
- [x] 5.3 Skipped per task note — no POST endpoint exists on this branch yet. Parse handler is exercised by the `malformedJsonBodyReturnsBadRequestPayload` test against the throwaway controller.
- [x] 5.4 `grep -rn` for the canonical taxonomy across `infrastructure/src/main/java` and `bootstrap/src/main/java` returns zero hits outside `GlobalExceptionHandler` — all references go through `CodeEnum`.
- [x] 5.5 Served document's `components.schemas.ErrorEnvelope.properties.code.enum` array is `['INSUFFICIENT_FUNDS', 'ACCOUNT_INACTIVE', 'RESOURCE_NOT_FOUND', 'BAD_REQUEST_PAYLOAD', 'INTERNAL_SERVER_ERROR']`, in canonical order.
- [x] 5.6 F00 ArchUnit `ModuleBoundaryTest` and F04 `NoApiDelegateTest` both pass.

## 6. Forward-compatibility hooks

- [x] 6.1 Class-level Javadoc on `GlobalExceptionHandler` lists the three future domain exceptions (`InsufficientFundsException`, `AccountInactiveException`, `ResourceNotFoundException`) with their owning capabilities and target codes.
- [x] 6.2 No stubs added — the exception classes don't exist yet.

## 7. Hygiene

- [x] 7.1 Deviations recorded below.
- [x] 7.2 Skimmed `ReadMe.md` and `INTRODUCTION.md` — both describe the contract-first concept; neither needs an edit for F03 (the user-facing description is unchanged).

## Implementation notes / deviations from design

- **Markdown table in `description` rejected by swagger-parser**: design.md proposed embedding the full code → HTTP mapping table inside the schema's top-level `description` as YAML block scalar (`|`). swagger-parser 2.1.22's snake-yaml safe-check throws `SnakeException` on that content (likely the pipe character density inside a block scalar tripping its depth heuristic). Description compressed to a single prose sentence referencing the api-error-contract capability; the full table lives in the published spec.
- **Validation handler covers two extra Spring exception types**: design.md listed `MethodArgumentNotValidException`, `BindException`, `ConstraintViolationException`. The `missingRequiredParameterReturnsBadRequestPayload` test surfaced that missing `@RequestParam` raises `MissingServletRequestParameterException`, which under design.md's catch list would fall through to the catch-all and return 500. Added `MissingServletRequestParameterException` and `MethodArgumentTypeMismatchException` to the validation handler's `@ExceptionHandler` value array. Both are framework-routine client errors and belong with the other 400 mappings.
- **Test placement under `bootstrap/src/test/java/com/bank/core/web/error/`**: mirrors F04's pattern (Spring context tests need the `@SpringBootApplication` from `bootstrap`). Design.md already specified this location.
- **No widening of `add-mappings: false` exception list needed**: design.md flagged risk that Springdoc's static-resource handler could break under `add-mappings: false`. Verified: Springdoc registers its own `ResourceHandlerRegistration` separately, so disabling Spring's default did not affect the dev-profile UI. F04 `SwaggerUiDevProfileTest` still passes against the modified config.
- **No POST endpoint manual curl in 5.3**: skipped because the branch has no POST endpoint yet. Parse handler is fully exercised by `malformedJsonBodyReturnsBadRequestPayload` against the throwaway test controller.
