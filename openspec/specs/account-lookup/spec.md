# Account Lookup

## Purpose

Read-only HTTP endpoint that returns the current state of a single account, addressed by its account number. The endpoint is idempotent and never mutates state.

## Requirements

### Requirement: GET endpoint returns account state

The service SHALL expose `GET /api/v1/accounts/{accountNumber}` returning HTTP `200` with a body containing the account number, current balance, and current status. The response SHALL match the schema declared in the OpenAPI contract exactly — no extra fields, no missing required fields.

#### Scenario: Existing account returns 200 with current state
- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` for an existing account
- **THEN** the response is HTTP 200 with a body carrying the matching account number, the current balance, and the current status, all conforming to the OpenAPI schema

#### Scenario: Balance reflects committed transfers immediately
- **WHEN** a transfer involving account A commits and a client immediately reads A
- **THEN** the returned balance reflects the committed transfer — no staleness window relative to completed transactions

#### Scenario: Read is idempotent
- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` repeatedly within a single committed state
- **THEN** every response body is identical

### Requirement: Missing account returns 404

A request for an account that does not exist SHALL return HTTP `404` with the standard error envelope, code `RESOURCE_NOT_FOUND`, and a message naming the missing account number.

#### Scenario: Unknown account returns RESOURCE_NOT_FOUND
- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` for an account that does not exist
- **THEN** the response is HTTP 404 with `code = RESOURCE_NOT_FOUND` (per [[api-error-contract]]) and the message references the missing account number

### Requirement: Status enum covers every returnable status

The status enum declared in the OpenAPI contract for this endpoint SHALL be wide enough to represent every status the lookup could ever return for a non-deleted account.

#### Scenario: Closed account is representable
- **WHEN** the lookup returns an account whose status is `Closed`
- **THEN** the response status field is `CLOSED` (or whichever serialised form the contract declares) and validates against the published enum — the endpoint does not return 500 because of a missing enum value

### Requirement: Endpoint is read-only

The endpoint SHALL NOT mutate any state — no balance change, no status change, no journal entry.

#### Scenario: Lookup does not write to the ledger
- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}`
- **THEN** the journal entry count and the account's stored state are identical before and after the request
