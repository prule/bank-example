## MODIFIED Requirements

### Requirement: Canonical code-to-HTTP mapping

The system SHALL use the following canonical error codes with their stated HTTP status mappings:

| Code                            | HTTP | Cause                                                       |
|---------------------------------|------|-------------------------------------------------------------|
| `INSUFFICIENT_FUNDS`            | 400  | Debit would leave source account at or below zero           |
| `ACCOUNT_INACTIVE`              | 400  | A non-Active account was targeted by an operation           |
| `RESOURCE_NOT_FOUND`            | 404  | Account or other addressed resource does not exist          |
| `BAD_REQUEST_PAYLOAD`           | 400  | Incoming payload violated declared constraints (validation) |
| `CONCURRENT_IDEMPOTENT_REQUEST` | 409  | Idempotency-Key collision while the first request is in flight |
| `IDEMPOTENCY_KEY_REUSED`        | 422  | Idempotency-Key seen before with a different request body   |
| `INTERNAL_SERVER_ERROR`         | 500  | Catch-all for unhandled failures                            |

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

#### Scenario: Concurrent Idempotency-Key collision maps to 409 CONCURRENT_IDEMPOTENT_REQUEST

- **WHEN** an endpoint rejects a request because a prior request bearing the same `Idempotency-Key` is still in flight
- **THEN** the response is HTTP 409 with `code = CONCURRENT_IDEMPOTENT_REQUEST`

#### Scenario: Idempotency-Key reuse with different body maps to 422 IDEMPOTENCY_KEY_REUSED

- **WHEN** an endpoint rejects a request because the same `Idempotency-Key` was already used with a different request body
- **THEN** the response is HTTP 422 with `code = IDEMPOTENCY_KEY_REUSED`
