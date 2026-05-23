# API Error Contract

## Purpose

Define the single shape used for every error returned by the public API and the set of stable error codes callers can rely on, so that clients can branch retry, surfacing, and alerting on machine-parseable values rather than guessing from prose.

## Requirements

### Requirement: Stable error envelope shape

Every error response from the public API SHALL have exactly three top-level fields: `code`, `message`, `timestamp`. No additional fields SHALL be present.

#### Scenario: Error response has only the three documented fields
- **WHEN** any endpoint returns an error response
- **THEN** the JSON body has exactly the keys `code`, `message`, `timestamp` — no more, no fewer

#### Scenario: Timestamp is ISO-8601 with timezone
- **WHEN** an error response is produced
- **THEN** the `timestamp` field is the response generation time formatted in ISO-8601 with timezone offset

#### Scenario: Code is uppercase identifier
- **WHEN** an error response is produced
- **THEN** the `code` field is a short uppercase identifier using underscores between words, intended for client-side switch statements

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

### Requirement: Payload validation produces BAD_REQUEST_PAYLOAD

A request that fails contract validation (missing required field, value out of range, malformed JSON) SHALL produce HTTP 400 with `code = BAD_REQUEST_PAYLOAD` and a message that identifies at least one offending field.

#### Scenario: Missing required field names the field
- **WHEN** a request omits a required field declared by the OpenAPI contract
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD` and the `message` identifies at least one offending field by name

### Requirement: Catch-all does not leak internals

Any unhandled exception SHALL produce HTTP 500 with `code = INTERNAL_SERVER_ERROR`. The `message` SHALL NOT contain stack traces, internal class names, package paths, SQL strings, or any other implementation detail. Full detail SHALL be written to server logs at error level.

#### Scenario: Internal failure returns generic message
- **WHEN** a handler throws an unanticipated exception
- **THEN** the response is HTTP 500 with `code = INTERNAL_SERVER_ERROR` and a generic message, and the full stack trace appears in server logs at error level but NOT in the response body

### Requirement: Error envelope published in the OpenAPI contract

The error response schema SHALL be defined once in the OpenAPI contract (see [[contract-first-api]]) and referenced by every error response across every endpoint. No endpoint SHALL inline its own error schema.

#### Scenario: Every endpoint references the shared error schema
- **WHEN** the OpenAPI document is inspected
- **THEN** every error response (`4xx`, `5xx`) references the single shared error schema by `$ref`, not by inline definition
