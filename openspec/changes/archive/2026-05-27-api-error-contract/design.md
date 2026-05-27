## Context

Currently, the application lacks a centralized, standardized mechanism for handling exception boundaries. Uncaught domain or framework-level exceptions default to standard Spring Boot "Whitelabel" error screens or raw stack traces in development, resulting in mismatched structures, leaking internal package directories, and returning arbitrary HTTP status codes.

This design introduces a global `@RestControllerAdvice` that intercepts all exceptions thrown at the web API boundary and transforms them into standardized `ErrorEnvelope` DTO bodies.

## Goals / Non-Goals

**Goals:**
- Implement `GlobalExceptionHandler` extending `ResponseEntityExceptionHandler` inside the `infrastructure` module under `com.bank.core.infrastructure.web`.
- Standardize all error responses to the three-field `ErrorEnvelope` shape: `code`, `message`, `timestamp`.
- Support canonical exception mappings for custom domain exceptions (`InsufficientFundsException`, `AccountInactiveException`, etc.), Spring validation errors (`MethodArgumentNotValidException`), malformed JSON payloads (`HttpMessageNotReadableException`), unrecognized paths (`NoHandlerFoundException`), and generic unhandled exceptions.
- Ensure all HTTP 500 payloads redact internal implementation details (SQL statements, internal classes, stack traces) while logging the full exception at `ERROR` level on the server.

**Non-Goals:**
- Introducing authentication/authorization errors (which will be tackled in subsequent security features).
- Modifying the core domain module or changing the existing exception models (which must remain framework-free).

## Decisions

### 1. Centralized Global Exception Handler via `@RestControllerAdvice`
To enforce clean boundary discipline and decouple error formatting from endpoint controller logic, we will define a central `GlobalExceptionHandler` class.
- Extends `ResponseEntityExceptionHandler` to inherit clean overrides for standard Spring MVC network-level exceptions.
- Uses `@RestControllerAdvice` to target all controllers, ensuring standard serialization format (JSON) and mapping responses automatically.

*Rationale*: Guarantees that any REST endpoint automatically conforms to the same error contract without duplicating try-catch blocks in controllers.

### 2. Standard Spring Exceptions Overrides
We will override standard Spring helper methods to ensure that payload parsing and bean-validation errors generate the canonical envelope with a `BAD_REQUEST_PAYLOAD` code (HTTP 400):
- **`handleMethodArgumentNotValid`**: Extract the targeted validation failures. The `message` field will name the offending fields (e.g., `Field 'amount' is required` or `Field 'amount' must be positive`).
- **`handleHttpMessageNotReadable`**: Intercept malformed JSON parser failures. We will return a clear, human-readable message without echoing raw Jackson parser internal details.
- **`handleNoHandlerFoundException`**: Intercept requests targeting undefined URI paths, translating them to `RESOURCE_NOT_FOUND` (HTTP 404) with the standard envelope structure.

*Rationale*: Prevents the default Spring exception serialization format from leaking through, maintaining full envelope integrity.

### 3. Custom Domain Exception Mappings
Domain exceptions will be mapped specifically:
- `InsufficientFundsException` → `INSUFFICIENT_FUNDS` (HTTP 400)
- `AccountInactiveException` → `ACCOUNT_INACTIVE` (HTTP 400)
- `InvalidAmountException` → `BAD_REQUEST_PAYLOAD` (HTTP 400)
- `IllegalStatusTransitionException` → `BAD_REQUEST_PAYLOAD` (HTTP 400)

*Rationale*: Maps pure, framework-free domain domain violations to client-actionable REST statuses cleanly.

### 4. Catch-All Exception Redaction and Server Logging
Any unhandled exceptions (`Exception`, `RuntimeException`) will be caught by a generic handler:
- Returns `INTERNAL_SERVER_ERROR` (HTTP 500) with the message: `"An unexpected error occurred. Please contact support."`
- Logs the full exception context (including stack trace, request method, and path) at `ERROR` level using the SLF4J logger.
- Scans and strips any internal keywords (such as `java.`, `com.bank.core.`, SQL verbs) to guarantee zero leakage.

*Rationale*: Protects the server's internal environment details from malicious/external clients while preserving deep debugging logs for operators.

## Risks / Trade-offs

- **[Risk]** Uncaught DispatcherServlet Errors: Certain low-level exceptions (e.g. invalid content negotiation or container-level errors) might bypass the Spring Boot ControllerAdvice.
  - *Mitigation*: Ensure `spring.mvc.throw-exception-if-no-handler-found=true` and `spring.web.resources.add-mappings=false` are declared in `application.properties` (or `application.yaml`) to force all unmapped requests through `NoHandlerFoundException` and onto our advice.
