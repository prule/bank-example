## MODIFIED Requirements

### Requirement: POST endpoint accepts source, destination, amount

The service SHALL expose `POST /api/v1/transfers` accepting a JSON body with required fields `sourceAccountNumber` (string, `minLength: 1`), `destinationAccountNumber` (string, `minLength: 1`), and `amount` (number, `minimum: 0.01`). On success the response SHALL be HTTP `204 No Content` with an empty body; callers re-read the affected accounts via [[account-lookup]] for the new balances. The endpoint is generated from the OpenAPI contract; the controller SHALL implement the generated `TransfersApi.createTransfer(...)` interface and SHALL NOT define its own method signature. The path SHALL be declared in the served `/v3/api-docs` document with `operationId: createTransfer`.

The endpoint SHALL ALSO accept an optional `Idempotency-Key` request header per [[transfer-idempotency]]. Requests without the header behave exactly as specified by the rest of this requirement; requests with the header are routed through the idempotency store, which on a successful first occurrence STILL executes the full pipeline described below (debit, credit, journal entry, locks) and on a replay short-circuits to the stored response without re-executing the pipeline.

#### Scenario: Valid transfer returns 204 with empty body

- **WHEN** a client `POST`s `{ "sourceAccountNumber": "ACC-001", "destinationAccountNumber": "ACC-002", "amount": 25.00 }` against two Active accounts with sufficient funds
- **THEN** the response is HTTP 204 with an empty body, a subsequent `GET /api/v1/accounts/ACC-001` shows the source balance reduced by 25.00, a subsequent `GET /api/v1/accounts/ACC-002` shows the destination balance increased by 25.00, and exactly one new `PENDING` journal entry exists

#### Scenario: OpenAPI contract declares the createTransfer operation

- **WHEN** the served OpenAPI document is inspected
- **THEN** it declares `POST /api/v1/transfers` with `operationId: createTransfer`, a request body referencing the `TransferRequest` schema, a 204 response, and 400 / 404 / 409 / 422 responses referencing `ErrorEnvelope`, AND an optional `header` parameter named `Idempotency-Key` of type string with `minLength: 1` and `maxLength: 200`

#### Scenario: Header-bearing first request runs the pipeline exactly once

- **WHEN** a client `POST`s a valid transfer with `Idempotency-Key: <fresh-uuid>`
- **THEN** the pipeline runs to completion (debit, credit, journal entry, lock acquisition) exactly as without the header
- **AND** a row is committed to `idempotency_key` with the response status and body, in the same transaction as the pipeline writes
