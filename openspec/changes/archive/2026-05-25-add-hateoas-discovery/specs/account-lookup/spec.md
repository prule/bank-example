## MODIFIED Requirements

### Requirement: GET endpoint returns account state

The service SHALL expose `GET /api/v1/accounts/{accountNumber}` returning HTTP `200` with a body containing exactly the fields `accountNumber` (string), `balance` (string, decimal with exactly two fraction digits, matching the regex `^\d+\.\d{2}$`), `status` (string, one of `ACTIVE`, `SUSPENDED`, `CLOSED`), and `_links` (HAL link map per [[hateoas-discovery]]). `_links` SHALL contain at minimum the relations `self` (pointing at `/api/v1/accounts/{accountNumber}`) and `transfers` (pointing at `/api/v1/transfers`). The response body SHALL match the `AccountResponse` schema declared in the OpenAPI contract exactly — no extra fields, no missing required fields. The endpoint is generated from the OpenAPI contract; the controller SHALL implement the generated `AccountsApi.lookupAccount(...)` interface and SHALL NOT define its own method signature.

#### Scenario: Existing account returns 200 with current state

- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` for an account whose persisted balance is `100.00` and status is `ACTIVE`
- **THEN** the response is HTTP 200, content-type `application/json` (or `application/hal+json` per request `Accept`), and the body has exactly four JSON keys (`accountNumber`, `balance`, `status`, `_links`) with values matching the persisted state (`balance` serialised as `"100.00"`, status serialised as `"ACTIVE"`, `_links.self.href` = `/api/v1/accounts/{accountNumber}`, `_links.transfers.href` = `/api/v1/transfers`)

#### Scenario: Balance reflects committed transfers immediately

- **WHEN** a `@Transactional` write commits a new balance for account A via the `Accounts` port (e.g. a test simulating a future fund-transfer use case), then a client immediately reads A
- **THEN** the returned balance equals the post-commit value with no staleness window; the adapter does not cache loaded aggregates

#### Scenario: Read is idempotent

- **WHEN** a client requests the same `GET /api/v1/accounts/{accountNumber}` three times within a single committed state
- **THEN** all three response bodies are byte-for-byte identical and all return HTTP 200

#### Scenario: Body shape matches the OpenAPI schema

- **WHEN** the served OpenAPI document at `/v3/api-docs` is inspected
- **THEN** it declares a `GET /api/v1/accounts/{accountNumber}` operation with `operationId: lookupAccount`, a path parameter `accountNumber` of type string, and a 200 response referencing the `AccountResponse` schema whose required fields are `accountNumber`, `balance`, `status`, and `_links`

#### Scenario: Self and transfers links point at correct paths

- **WHEN** a client reads `GET /api/v1/accounts/CUST-1001`
- **THEN** the response `_links.self.href` is `/api/v1/accounts/CUST-1001` (non-templated), and `_links.transfers.href` is `/api/v1/transfers` (non-templated)
