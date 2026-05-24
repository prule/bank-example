## Context

F04 just shipped: the canonical OpenAPI contract lives under [bootstrap/src/main/resources/openapi/](bootstrap/src/main/resources/openapi/) with a placeholder `ErrorEnvelope` schema (`code` is a free `string`), every `5XX` response in the contract `$ref`s it, the generator emits a `com.bank.core.dto.ErrorEnvelope` DTO into `infrastructure/build/generated/openapi/`, and the [OpenApiController](infrastructure/src/main/java/com/bank/core/infrastructure/web/OpenApiController.java) serves the canonical contract at `/v3/api-docs` under all profiles plus Swagger UI under `dev`.

What still doesn't exist on this branch: any real business endpoint that could throw an error, any domain exception classes (F01/F06 land them later in the manifest's build order), or a global exception handler. F03 closes that gap on the API side now so F05/F06/F08 can hand off errors to a known shape without inventing one each.

Constraints inherited from F00 and F04:
- The handler lives in `infrastructure` (Spring is allowed there; F00 ArchUnit forbids it in `domain` and `application`).
- The error envelope is owned by the OpenAPI document — not by a hand-written Java class. The generator's `ErrorEnvelope` DTO is the only Java representation; F03 just tightens the schema.
- No leaking of stack traces, internal class names, SQL fragments, package paths, or framework details into client-visible `message` fields.
- F04's "every 4xx/5xx response references `ErrorEnvelope` by `$ref`" rule continues to hold; F03 only tightens the schema's internal definition.

Open decisions from the manifest that touch F03 indirectly: `account-status-enum-coverage` (CLOSED accounts in lookup responses — F05's concern, not F03's). `idempotency` (no retry codes today — out of scope here).

## Goals / Non-Goals

**Goals:**
- One Java class — `com.bank.core.infrastructure.web.error.GlobalExceptionHandler`, annotated `@RestControllerAdvice` — owns every conversion from Java exception to HTTP response. No business endpoint ships its own `@ExceptionHandler`.
- The handler returns the generated `com.bank.core.dto.ErrorEnvelope` directly, populating `code` from the typed `ErrorEnvelope.CodeEnum` emitted by the generator.
- Validation failures (`MethodArgumentNotValidException`, `ConstraintViolationException`) produce `400 BAD_REQUEST_PAYLOAD` with a `message` that names at least one offending field.
- Malformed bodies (`HttpMessageNotReadableException`, including missing required JSON, malformed JSON) produce `400 BAD_REQUEST_PAYLOAD` with a generic but specific message — never the raw parser exception text.
- Missing paths/handlers (`NoHandlerFoundException`, `NoResourceFoundException`) produce `404 RESOURCE_NOT_FOUND`. Spring's Whitelabel error page is suppressed.
- The catch-all `@ExceptionHandler(Exception.class)` produces `500 INTERNAL_SERVER_ERROR` with the generic message `"An unexpected error occurred. Please contact support."` (no specifics). The full exception, including stack trace, request method, and request path, is logged at `ERROR` level. F03 does not propagate any field of the exception into the response body.
- Per-handler integration tests in `bootstrap/src/test/java/com/bank/core/web/error/` exercise each handler against a `@TestConfiguration`-scoped throwaway controller. Assertions: HTTP status, `code` value, `timestamp` is parseable as ISO-8601 with offset, body has exactly the three documented keys, `message` is free of internal markers (no `java.`, `org.springframework.`, `at com.`, no SQL).
- The schema tightening regenerates a typed `CodeEnum` on the next build; the generated DTO is consumed by the handler with no parallel hand-written enum.
- Operability: every 500 logs at ERROR level with method, path, and exception. Validation 400s log at INFO (expected client error). Catch-all does not log validation failures twice.

**Non-Goals:**
- Adding `@ExceptionHandler` entries for domain exceptions (`InsufficientFundsException`, `AccountInactiveException`). Those exception classes do not exist yet on this branch; F01 (introduces them) and F06 (the transfer endpoint that throws them) wire them into the same handler. F03 makes that one-line per exception.
- Resource-not-found mapping for *business* missing-resources (e.g. account not found). The 404 handler in F03 catches *Spring's* not-found exceptions (no controller matched, static resource missing). F05's account-lookup controller will throw a domain/application `ResourceNotFoundException` and F05 adds the corresponding `@ExceptionHandler` to map it. F03 deliberately stops short of inventing that exception class — that's F05's vocabulary.
- Idempotency keys, retry advice, problem-details (`application/problem+json`), localization. All out of scope; the envelope is fixed at the published shape.
- Auth-related errors (`401`, `403`). Auth is out of scope for v2-sdd; if/when added, the same handler grows new entries.
- ProblemDetail / RFC 7807 migration. The envelope is project-specific by design.

## Decisions

### Where the handler lives

`infrastructure/src/main/java/com/bank/core/infrastructure/web/error/GlobalExceptionHandler.java` annotated `@RestControllerAdvice`. Adjacent to the existing `OpenApiController`. The `error` sub-package isolates the handler from any future business controllers and makes the F00 ArchUnit web-package rule unambiguous.

Rejected alternative: `bootstrap/src/main/java/com/bank/core/config/...`. Bootstrap is for wiring, not handlers. Spring picks the advice up from `infrastructure` via the existing component scan rooted at `com.bank.core`.

### Schema tightening: enum on `ErrorEnvelope.code`

Change [bootstrap/src/main/resources/openapi/schemas/error-envelope.yaml](bootstrap/src/main/resources/openapi/schemas/error-envelope.yaml) so `code` becomes:

```yaml
code:
  type: string
  description: Canonical error code, drawn from a fixed taxonomy.
  enum:
    - INSUFFICIENT_FUNDS
    - ACCOUNT_INACTIVE
    - RESOURCE_NOT_FOUND
    - BAD_REQUEST_PAYLOAD
    - INTERNAL_SERVER_ERROR
```

Also rewrite the schema-level `description` to embed the canonical code → HTTP mapping table (mirrors the published spec). The OpenAPI Generator's `spring` generator emits this as a public nested enum `ErrorEnvelope.CodeEnum` with `getValue()` and `fromValue(String)`. The generated DTO becomes the single Java representation of the taxonomy.

Rejected alternatives:
- **Hand-written `com.bank.core.api.ErrorCode` enum**: parallel definition next to the generated one, two sources of truth, easy to drift. Hard pass.
- **`x-enum-varnames` extension** to customise enum naming: unnecessary — the canonical names are already valid Java identifiers.
- **Per-endpoint enums** (only the codes that particular endpoint can return): tighter typing but the spec says "every error response references the *single* shared envelope" — splitting the enum breaks that.

### Handler topology

Single `GlobalExceptionHandler` with five `@ExceptionHandler` methods (one per code, except the catch-all which is the fifth). Each method:

1. Builds an `ErrorEnvelope` with the matching `CodeEnum`, a method-specific `message`, and `timestamp = OffsetDateTime.now(ZoneOffset.UTC)`.
2. Returns `ResponseEntity.status(<http-status>).body(envelope)`.

Pseudo-shape:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, BindException.class})
    ResponseEntity<ErrorEnvelope> handleValidation(Exception ex, HttpServletRequest req);  // 400 BAD_REQUEST_PAYLOAD

    @ExceptionHandler({HttpMessageNotReadableException.class})
    ResponseEntity<ErrorEnvelope> handleParse(HttpMessageNotReadableException ex, HttpServletRequest req);  // 400 BAD_REQUEST_PAYLOAD

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorEnvelope> handleNotFound(Exception ex, HttpServletRequest req);  // 404 RESOURCE_NOT_FOUND

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> handleCatchAll(Exception ex, HttpServletRequest req);  // 500 INTERNAL_SERVER_ERROR
}
```

Reason for splitting validation handlers from a single `Exception.class` switch: Spring's advice mechanism dispatches to the most-specific matching method automatically. Explicit signatures make it obvious which exceptions are handled and which fall through to the catch-all.

Rejected: a `Map<Class<? extends Throwable>, ErrorMapping>` lookup table with a single handler method. Cleaner-looking but worse for IDE navigation and stack traces. Five methods are fine.

### Message construction (no leakage)

- Validation (400 BAD_REQUEST_PAYLOAD): for `MethodArgumentNotValidException`, iterate `getBindingResult().getFieldErrors()`, take up to three fields, format as `"Validation failed: field 'X' <error>, field 'Y' <error>"`. For `ConstraintViolationException`, use the property paths. Never include the raw exception class name or `org.springframework.*` text.
- Parse (400 BAD_REQUEST_PAYLOAD): a static `"Malformed request body."` Do not extract Jackson's `JsonMappingException` location string — it sometimes embeds class names from the target DTO. Static message wins.
- Not found (404 RESOURCE_NOT_FOUND): `"No handler found for " + req.getMethod() + " " + req.getRequestURI()`. Path is the *requested* URI, which the client already knows, so no information leak.
- Catch-all (500 INTERNAL_SERVER_ERROR): static `"An unexpected error occurred. Please contact support."` The exception itself goes to logs only.

### Logging

A single `Logger` field. Levels:
- Validation 400s → `INFO` (expected client error, useful for observability but not noise).
- Parse 400s → `INFO`.
- Not-found 404s → `INFO`.
- Catch-all 500s → `ERROR`, with the full exception and a structured prefix: `"Unhandled exception while serving {} {}: "` followed by the exception. Stack trace is appended by the SLF4J `Throwable` overload.

Rejected: `WARN` for client errors. The system isn't warning — the *client* sent something invalid. INFO is the right level.

### `application.yaml` knobs

Add to `bootstrap/src/main/resources/application.yaml` so `NoHandlerFoundException` actually fires:

```yaml
spring:
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
```

`add-mappings: false` disables Spring's default static-resource handler. Otherwise unknown paths get served by the static-resource handler, returning 404 Whitelabel without throwing. `throw-exception-if-no-handler-found: true` lets `DispatcherServlet` raise `NoHandlerFoundException` for unmatched routes so the advice can catch it.

Side-effect to be aware of: anything that depends on a static `/error` page is also disabled. The advice handles errors; we don't want a static fallback.

Rejected alternatives:
- A `WebMvcConfigurer` overriding `configureDefaultServletHandling`: more code than needed; the two properties express the same intent declaratively.
- Spring Boot's `ErrorController` SPI: legacy. `@RestControllerAdvice` is the modern way and gives us the same JSON envelope from one place.

### Test strategy

Tests live in `bootstrap/src/test/java/com/bank/core/web/error/` (Spring context tests need the `@SpringBootApplication` from `bootstrap`, mirroring F04's placement decision). Two test classes:

1. `GlobalExceptionHandlerTest` — uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a `@TestConfiguration` that registers a throwaway test controller (`ErrorTestController`) under `/internal/test-errors/*`. The controller declares endpoints that deliberately throw each kind of exception (one for validation via a `@Valid` body, one for explicit `throw new RuntimeException("boom")`, etc.). The test uses `TestRestTemplate` to hit those endpoints and asserts: status code, envelope `code`, envelope `timestamp` parseable as ISO-8601-with-offset, body has exactly three keys, `message` does not contain banned substrings.
2. `NotFoundHandlerTest` — separate so it can rely on Spring's default no-handler dispatch path without a fake controller.

Throwaway controller is `@RestController @RequestMapping("/internal/test-errors")`, registered only inside the test's `@TestConfiguration`, never on the production classpath. ArchUnit's existing `NoApiDelegateTest` (added by F04) and the F00 boundary tests do not touch it because they scan production sources only (`ImportOption.DoNotIncludeTests`).

### F00 `@Transactional`-in-application open question

Not relevant here: the handler is in `infrastructure`, not `application`. No new ArchUnit rule needed.

### Forward compatibility with F01/F06

When F01 introduces `InsufficientFundsException` and `AccountInactiveException` in `com.bank.core.domain.exceptions` (their natural home), F06 adds two methods to `GlobalExceptionHandler`:

```java
@ExceptionHandler(InsufficientFundsException.class)
ResponseEntity<ErrorEnvelope> handleInsufficientFunds(...);  // 400 INSUFFICIENT_FUNDS

@ExceptionHandler(AccountInactiveException.class)
ResponseEntity<ErrorEnvelope> handleAccountInactive(...);  // 400 ACCOUNT_INACTIVE
```

F05 adds the same kind of one-liner for `ResourceNotFoundException` (a new application-layer exception introduced in F05 to map "account not found by number" to the canonical 404 envelope).

The handler class is intentionally short enough to read in one screen so each future capability sees exactly where to slot its mapping.

## Risks / Trade-offs

- **Spring 6.x changed how 404s flow** → in Spring Boot 3.4, `NoHandlerFoundException` requires `throw-exception-if-no-handler-found=true` AND `spring.web.resources.add-mappings=false`. Mitigation: both knobs are in `application.yaml`; the `NotFoundHandlerTest` proves the round-trip.
- **Springdoc under `dev` profile exposes the docs UI** → the dev profile already redirects `/swagger-ui.html` to `/swagger-ui/index.html`. With `add-mappings=false`, will the Swagger UI static assets still load? They're served by Springdoc's own `ResourceHandlerRegistry`, not Spring's default. Mitigation: F04's `SwaggerUiDevProfileTest` already asserts the UI loads; running it after this change validates the assumption. If it breaks, narrow `add-mappings=false` to a `WebMvcConfigurer` that excludes Springdoc paths.
- **`add-mappings=false` is also set under the `test` profile by inheritance** → desirable; test profile shouldn't serve static resources either.
- **Validation `message` text variability** → the message format `"Validation failed: field 'X' <error>"` is human-readable but inherently variable across locales and validation contexts. The spec says "clients SHALL NOT pattern-match on its content"; tests assert presence of *one* offending field name, not the exact message string. This keeps the spec honest without freezing message text.
- **Catch-all may swallow Spring framework startup errors** → `@RestControllerAdvice` only runs for handler dispatch, not application startup. Spring Boot's own startup failure handling is unchanged. Verified by intuition; not testable here.
- **Generated `ErrorEnvelope` setters mutate state** → the generated DTO uses setters and is mutable. The handler builds it fresh per-request via setters or the all-args constructor; no shared instance. Threading concern is nil. If F04's generator output ever switches to records, the handler updates accordingly.
- **Schema enum value drift** → if a future change adds a new error code, the OpenAPI document must be edited and `./gradlew build` rerun before any new handler entry compiles. Generator + Gradle's `dependsOn("openApiGenerate")` from F04 enforce this automatically — adding a Java handler entry that references a non-existent `CodeEnum.FOO` fails compile.
- **`HttpMessageNotReadableException` is also raised by Jackson when deserialising into a generated DTO with a constrained enum field** → e.g. if a future endpoint accepts an `ErrorCode` in the body and a client sends `"BANANAS"`, Jackson throws this exception. Mitigation: the parse handler covers it; the `message` stays static, so the bogus enum value never echoes back to the client.

## Migration Plan

- **Deploy**: Land via PR. Reviewer runs `./gradlew clean build` — generator picks up the tightened schema, regenerates `ErrorEnvelope.CodeEnum`, compiles the handler, all tests pass. `./gradlew :bootstrap:bootRun` then `curl -i http://localhost:8080/no/such/path` returns `404 application/json` with `{"code":"RESOURCE_NOT_FOUND","message":"No handler found for GET /no/such/path","timestamp":"..."}`.
- **Rollback**: This change touches the error-envelope schema (`code` from string → enum), adds one handler class, adds two `application.yaml` keys, adds tests. All additive or refinements. `git revert` cleanly reverses it.
- **Forward path**: F01 adds the domain exception classes; F05 and F06 add their `@ExceptionHandler` entries to the same handler. The schema's enum may gain new values (e.g. `IDEMPOTENCY_CONFLICT`) — every addition is one new line in the schema, one new line in the handler, one new test.

## Open Questions

- **Should `INTERNAL_SERVER_ERROR` carry a server-generated correlation ID in the `message`?** The spec says `message` is human-readable for support. A short opaque ID (`"ref:abc-123"`) would let operators find the matching log line without leaking internals. Not in F03 to avoid scope creep, but flag it as a v3-clean candidate.
- **Validation message localisation**: deferred. The system has no `Accept-Language` story; English-only for now.
- **HEAD / OPTIONS unmatched paths**: Spring sometimes special-cases HEAD vs GET. Spot-check during implementation; if HEAD against an unknown path bypasses the advice, fix in the handler. Listed in `tasks.md` as a verification step.
- **Should the handler also `$ref` the envelope in non-error responses (`200` content negotiation 406s, etc.)?** F04 only requires `4xx`/`5xx` to `$ref`. 406 is `4xx`, so yes — and Spring's built-in 406 path doesn't currently go through the handler. Verification step in `tasks.md` decides whether F03 also adds a `HttpMediaTypeNotAcceptableException` handler now or defers. Default-to-add unless it breaks other tests.
