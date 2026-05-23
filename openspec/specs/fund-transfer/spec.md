# Fund Transfer

## Purpose

Endpoint that moves a positive amount of money from one Active account to another Active account atomically, producing exactly one balanced journal entry. Either the transfer commits in full or has no observable effect.

## Requirements

### Requirement: POST endpoint accepts source, destination, amount

The service SHALL expose `POST /api/v1/transfers` accepting a request body that carries a source account number, a destination account number, and a positive amount with minimum `0.01`. On success the response SHALL be HTTP `204 No Content` with an empty body; callers re-read the affected accounts via [[account-lookup]] for the new balances.

#### Scenario: Valid transfer returns 204 with empty body
- **WHEN** a client `POST`s a valid transfer between two Active accounts with sufficient funds
- **THEN** the response is HTTP 204 with an empty body, both account balances reflect the move exactly on subsequent reads, and a journal entry is produced (see other requirements)

### Requirement: Payload validation

Requests with a missing source, missing destination, or missing/zero/negative amount SHALL be rejected with HTTP `400 BAD_REQUEST_PAYLOAD` per [[api-error-contract]]. No balance change, no journal entry.

#### Scenario: Missing source is rejected
- **WHEN** a client `POST`s a transfer omitting the source account number
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD`, no balances change, no journal entry is created

#### Scenario: Non-positive amount is rejected
- **WHEN** a client `POST`s a transfer with amount zero, negative, or absent
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD`, no balances change, no journal entry is created

### Requirement: Business-rule rejections map per error contract

Business-rule failures SHALL map per [[api-error-contract]]:
- Source or destination not found → `404 RESOURCE_NOT_FOUND`
- Either account not Active → `400 ACCOUNT_INACTIVE`
- Source has insufficient funds (per [[account-domain]]) → `400 INSUFFICIENT_FUNDS`

In every rejection case, no balance SHALL change and no journal entry SHALL be created.

#### Scenario: Missing account returns 404
- **WHEN** a client `POST`s a transfer where the source or destination does not exist
- **THEN** the response is HTTP 404 with `code = RESOURCE_NOT_FOUND`, no balances change, no journal entry is created

#### Scenario: Suspended endpoint returns ACCOUNT_INACTIVE
- **WHEN** a client `POST`s a transfer where the source or destination is Suspended
- **THEN** the response is HTTP 400 with `code = ACCOUNT_INACTIVE`, no balances change, no journal entry is created

#### Scenario: Insufficient funds returns INSUFFICIENT_FUNDS
- **WHEN** a client `POST`s a transfer that would overdraw the source per [[account-domain]]
- **THEN** the response is HTTP 400 with `code = INSUFFICIENT_FUNDS`, both balances are unchanged, no journal entry is created

### Requirement: Atomicity of balance and ledger writes

A successful transfer SHALL commit, as a single atomic unit: the debit on the source account, the credit on the destination account, and exactly one journal entry (per [[immutable-ledger]]) containing two movements — a debit on the source and a credit on the destination, both for the requested amount. On any failure (validation, business rule, infrastructure error, crash mid-flight) NONE of these effects SHALL be observable.

#### Scenario: Successful transfer produces exactly one balanced journal
- **WHEN** a transfer between Active accounts with sufficient funds commits
- **THEN** exactly one new journal entry exists with status `Pending`, containing one debit movement on the source and one credit movement on the destination, both for the requested amount; sum of credit movements equals sum of debit movements

#### Scenario: Failure mid-flight leaves no partial state
- **WHEN** a transfer rolls back (crash, exception, infrastructure failure) after the source has been touched but before commit
- **THEN** both account balances are unchanged from their pre-transfer state and no journal entry, no orphaned movements, and no log of partial work persist

### Requirement: Journal description and status on creation

The journal entry produced by a transfer SHALL carry a description identifying the source and destination account numbers, a timestamp equal to the moment the transfer was processed, and status `Pending`. Promotion to `Verified` is the responsibility of [[journal-verification]].

#### Scenario: Journal description names both accounts
- **WHEN** a transfer commits
- **THEN** the persisted journal entry's description identifies the source and destination account numbers and the timestamp equals the processing moment, and the status is `Pending`
