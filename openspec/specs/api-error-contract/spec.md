# API Error Contract

## Purpose

Define the single shape used for every error returned by the public API and the set of stable error codes callers can rely on, so that clients can branch retry, surfacing, and alerting on machine-parseable values rather than guessing from prose.

## Requirements

### Requirement: Stable error envelope shape

Every error response from the public API SHALL have exactly three top-level fields: `code`, `message`, `timestamp`. No additional fields SHALL be present in any error response.

#### Scenario: Error response has only the three documented fields

- **WHEN** any endpoint returns an error response
- **THEN** the JSON body has exactly the keys `code`, `message`, `timestamp` — no more, no fewer

#### Scenario: Timestamp is ISO-8601 with timezone

- **WHEN** an error response is produced
- **THEN** the `timestamp` field is the response generation time formatted in ISO-8601 with timezone offset (e.g. `2026-05-24T01:00:00Z`)

#### Scenario: Code is an uppercase identifier

- **WHEN** an error response is produced
- **THEN** the `code` field is a short uppercase identifier using underscores between words, drawn from the canonical taxonomy defined below, intended for client-side switch statements

#### Scenario: Message is human-readable, never the contract

- **WHEN** an error response is produced
- **THEN** the `message` field is a human-readable English string intended for support and log surfaces; clients SHALL NOT pattern-match on its content

### Requirement: Canonical code-to-HTTP mapping

The system SHALL use the following canonical error codes with their stated HTTP status mappings:

| Code                    | HTTP | Cause                                                       |
|-------------------------|------|-------------------------------------------------------------|
| `INSUFFICIENT_FUNDS`    | 400  | Debit would leave source account at or below zero           |
| `ACCOUNT_INACTIVE`      | 400  | A non-Active account was targeted by an operation           |
| `RESOURCE_NOT_FOUND`    | 404  | Account or other addressed resource does not exist          |
| `BAD_REQUEST_PAYLOAD`   | 400  | Incoming payload violated declared constraints (validation) |
| `INTERNAL_SERVER_ERROR` | 500  | Catch-all for unhandled failures                            |

Codes SHALL NEVER be renamed once published; new codes MAY be added.

#### Scenario: Insufficient funds maps to 400 INSUFFICIENT_FUNDS

- **WHEN** an endpoint rejects a debit because it would overdraw the source
- **THEN** the response is HTTP 400 with `code = INSUFFICIENT_FUNDS`

#### Scenario: Non-Active account maps to 400 ACCOUNT_INACTIVE

- **WHEN** an endpoint targets a Suspended or Closed account
- **THEN** the response is HTTP 400 with `code = ACCOUNT_INACTIVE`

#### Scenario: Missing resource maps to 404 RESOURCE_NOT_FOUND

- **WHEN** an endpoint addresses a resource (e.g. account) that does not exist
- **THEN** the response is HTTP 404 with `code = RESOURCE_NOT_FOUND`

#### Scenario: 404 is only for missing resources, not for business rules

- **WHEN** an endpoint rejects a debit due to insufficient funds
- **THEN** the response is HTTP 400, NOT 404

#### Scenario: Unknown URL path returns the canonical 404 envelope

- **WHEN** a client requests a URL with no matching handler (e.g. `GET /no/such/path`)
- **THEN** the response is HTTP 404 with the canonical envelope body and `code = RESOURCE_NOT_FOUND`, NOT Spring's Whitelabel error page

### Requirement: Payload validation produces BAD_REQUEST_PAYLOAD

A request that fails contract validation (missing required field, value out of range, type mismatch, malformed JSON body) SHALL produce HTTP 400 with `code = BAD_REQUEST_PAYLOAD`. The `message` SHALL identify at least one offending field by name for bean-validation failures; for malformed JSON bodies the `message` MAY be a static string and SHALL NOT echo back the raw parser exception text.

#### Scenario: Missing required field names the field

- **WHEN** a request omits a required field declared by the OpenAPI contract and bean validation runs
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD` and the `message` identifies at least one offending field by name

#### Scenario: Malformed JSON body returns BAD_REQUEST_PAYLOAD

- **WHEN** a request body cannot be parsed as JSON (e.g. truncated, invalid syntax) and the controller declares `application/json`
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD` and the `message` is human-readable without echoing the raw Jackson exception text

### Requirement: Catch-all does not leak internals

Any unhandled exception SHALL produce HTTP 500 with `code = INTERNAL_SERVER_ERROR`. The `message` field SHALL NOT contain stack traces, internal class names (`java.*`, `org.springframework.*`, `com.bank.core.*`), package paths, SQL strings, file system paths, or any other implementation detail. Full detail SHALL be written to server logs at `ERROR` level, including the request method and request path.

#### Scenario: Internal failure returns generic message

- **WHEN** a handler throws an unanticipated exception (e.g. a `RuntimeException`)
- **THEN** the response is HTTP 500 with `code = INTERNAL_SERVER_ERROR` and a generic message, and the full stack trace appears in server logs at `ERROR` level but NOT in the response body

#### Scenario: Internal failure message has no internal markers

- **WHEN** a 500 response body is inspected after a thrown exception
- **THEN** the `message` field contains no substring matching `java.`, `org.springframework.`, `com.bank.core.`, `at ` (stack-trace marker), or any SQL keyword (`SELECT`, `INSERT`, `UPDATE`, `DELETE`)

### Requirement: Error envelope published in the OpenAPI contract

The error response schema SHALL be defined once in the OpenAPI contract (see [[contract-first-api]]) and referenced by every error response across every endpoint. No endpoint SHALL inline its own error schema.

#### Scenario: Every endpoint references the shared error schema

- **WHEN** the OpenAPI document is inspected
- **THEN** every error response (`4xx`, `5xx`) references the single shared error schema by `$ref`, not by inline definition

#### Scenario: The schema's code field is a typed enum

- **WHEN** the `components.schemas.ErrorEnvelope.properties.code` definition is inspected in the OpenAPI document
- **THEN** the field's type is `string` with an `enum` constraint listing exactly the five canonical codes (`INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, `RESOURCE_NOT_FOUND`, `BAD_REQUEST_PAYLOAD`, `INTERNAL_SERVER_ERROR`); the OpenAPI generator emits a typed nested enum `ErrorEnvelope.CodeEnum` consumed by the global exception handler
