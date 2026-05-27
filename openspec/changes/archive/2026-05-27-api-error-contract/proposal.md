## Why

Currently, errors returned by the bank-core public REST API are not standardized. Establishing a uniform error response shape and mapping domain rule violations to stable, machine-parseable codes ensures that API clients can cleanly handle retries, surface validation alerts, and diagnose integration failures without relying on fragile text-pattern matching or exposing internal server implementation details.

## What Changes

- **Single Error Envelope**: Standardize all REST API error responses to return exactly three JSON fields: `code`, `message`, and `timestamp`.
- **OpenAPI Schema Gating**: Declare a shared, enum-constrained `ErrorEnvelope` schema in the modular OpenAPI specification and reference it across all endpoints.
- **Canonical Status Mapping**: Implement a centralized Spring Boot global exception handler that translates payload validation errors, unrecognized request paths, custom domain exceptions, and catch-all internal failures to specific HTTP status codes (400, 404, 409, 422, 500) and uppercase error identifiers (`INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, `RESOURCE_NOT_FOUND`, `BAD_REQUEST_PAYLOAD`, `INTERNAL_SERVER_ERROR`).
- **Internal Diagnostic Protection**: Ensure HTTP 500 responses use a generic message and strictly redact all package pathways, stack traces, class names, and SQL statements from the payload while logging the complete trace context at `ERROR` level on the server.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `api-error-contract`: Introduce the standardized error envelope, payload validation handler, and canonical mapping of core domain exceptions.

## Impact

- **Global Controller Advice**: A new exception handler will intercept all uncaught and Spring-level request processing exceptions.
- **REST Boundary Verification**: All existing and future integration tests will verify that negative endpoints yield standard error contracts.
