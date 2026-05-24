## ADDED Requirements

### Requirement: POST endpoint accepts source, destination, amount

The service SHALL expose `POST /api/v1/transfers` accepting a JSON body with required fields `sourceAccountNumber` (string, `minLength: 1`), `destinationAccountNumber` (string, `minLength: 1`), and `amount` (number, `minimum: 0.01`). On success the response SHALL be HTTP `204 No Content` with an empty body; callers re-read the affected accounts via [[account-lookup]] for the new balances. The endpoint is generated from the OpenAPI contract; the controller SHALL implement the generated `TransfersApi.createTransfer(...)` interface and SHALL NOT define its own method signature. The path SHALL be declared in the served `/v3/api-docs` document with `operationId: createTransfer`.

#### Scenario: Valid transfer returns 204 with empty body

- **WHEN** a client `POST`s `{ "sourceAccountNumber": "ACC-001", "destinationAccountNumber": "ACC-002", "amount": 25.00 }` against two Active accounts with sufficient funds
- **THEN** the response is HTTP 204 with an empty body, a subsequent `GET /api/v1/accounts/ACC-001` shows the source balance reduced by 25.00, a subsequent `GET /api/v1/accounts/ACC-002` shows the destination balance increased by 25.00, and exactly one new `PENDING` journal entry exists

#### Scenario: OpenAPI contract declares the createTransfer operation

- **WHEN** the served OpenAPI document is inspected
- **THEN** it declares `POST /api/v1/transfers` with `operationId: createTransfer`, a request body referencing the `TransferRequest` schema, a 204 response, and 400 / 404 responses referencing `ErrorEnvelope`

### Requirement: Payload validation

Requests with a missing source, missing destination, missing amount, zero amount, negative amount, or amount below `0.01` SHALL be rejected with HTTP `400 BAD_REQUEST_PAYLOAD` per [[api-error-contract]]. Rejection SHALL be performed by bean validation (`@NotNull`, `@Size(min = 1)`, `@DecimalMin("0.01")`) on the generated `TransferRequest` DTO so the failure is routed through F03's existing `MethodArgumentNotValidException` handler before any business logic runs. No balance change, no journal entry, no lock acquisition.

#### Scenario: Missing source is rejected

- **WHEN** a client `POST`s `{ "destinationAccountNumber": "ACC-002", "amount": 25.00 }` (no source field)
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, the message identifies the `sourceAccountNumber` field, the row count of `journal_entry` is unchanged, and no row in `account` is modified

#### Scenario: Missing destination is rejected

- **WHEN** a client `POST`s `{ "sourceAccountNumber": "ACC-001", "amount": 25.00 }` (no destination field)
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, no balance changes, no journal entry

#### Scenario: Zero amount is rejected

- **WHEN** a client `POST`s a transfer with `amount: 0`
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, no balance changes, no journal entry

#### Scenario: Negative amount is rejected

- **WHEN** a client `POST`s a transfer with `amount: -5.00`
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, no balance changes, no journal entry

#### Scenario: Amount below 0.01 is rejected

- **WHEN** a client `POST`s a transfer with `amount: 0.001`
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, no balance changes, no journal entry

#### Scenario: Self-transfer is rejected

- **WHEN** a client `POST`s a transfer with the same value for `sourceAccountNumber` and `destinationAccountNumber`
- **THEN** the response is HTTP 400 with `code = "BAD_REQUEST_PAYLOAD"`, the message names the offending account, no balance changes, no journal entry. (This closes the `self-transfer` open decision in [openspec/config.yaml](openspec/config.yaml).)

### Requirement: Business-rule rejections map per error contract

Business-rule failures SHALL map per [[api-error-contract]]:

- Source or destination account not found → `404 RESOURCE_NOT_FOUND` (thrown by the use case as `com.bank.core.domain.ResourceNotFoundException("account", number.value())`, mapped by F03's existing handler entry from F05).
- Either account not Active → `400 ACCOUNT_INACTIVE` (thrown by `Account.credit` / `Account.debit` as `AccountInactiveException`, mapped by a new F03 handler entry shipped in this change).
- Source has insufficient funds per [[account-domain]] → `400 INSUFFICIENT_FUNDS` (thrown by `Account.debit` as `InsufficientFundsException`, mapped by a new F03 handler entry shipped in this change).

In every rejection case, no balance SHALL change and no journal entry SHALL be created. Each new handler entry SHALL log at INFO level (not ERROR) because these are expected business-rule rejections.

#### Scenario: Missing source returns 404 RESOURCE_NOT_FOUND

- **WHEN** a client `POST`s a transfer where `sourceAccountNumber` references no account in the `account` table
- **THEN** the response is HTTP 404 with `code = "RESOURCE_NOT_FOUND"`, the message names the missing account number, no balance changes, no journal entry

#### Scenario: Missing destination returns 404 RESOURCE_NOT_FOUND

- **WHEN** a client `POST`s a transfer where `destinationAccountNumber` references no account
- **THEN** the response is HTTP 404 with `code = "RESOURCE_NOT_FOUND"`, the message names the missing account number, no balance changes, no journal entry

#### Scenario: Suspended source returns 400 ACCOUNT_INACTIVE

- **WHEN** a client `POST`s a transfer whose source account has status `SUSPENDED`
- **THEN** the response is HTTP 400 with `code = "ACCOUNT_INACTIVE"`, no balance changes, no journal entry

#### Scenario: Suspended destination returns 400 ACCOUNT_INACTIVE

- **WHEN** a client `POST`s a transfer whose destination account has status `SUSPENDED`
- **THEN** the response is HTTP 400 with `code = "ACCOUNT_INACTIVE"`, no balance changes, no journal entry

#### Scenario: Insufficient funds returns 400 INSUFFICIENT_FUNDS

- **WHEN** a client `POST`s a transfer that would leave the source balance at zero or below per [[account-domain]]
- **THEN** the response is HTTP 400 with `code = "INSUFFICIENT_FUNDS"`, both balances are unchanged, no journal entry

### Requirement: Atomicity of balance and ledger writes

A successful transfer SHALL commit, as a single atomic unit: the debit on the source account, the credit on the destination account, and exactly one journal entry (per [[immutable-ledger]]) containing two movements — one DEBIT on the source account and one CREDIT on the destination account, both for the requested amount. On any failure (validation, business rule, infrastructure error, exception thrown inside the use case) NONE of these effects SHALL be observable. The transactional boundary SHALL be the `@Transactional` annotation on `TransferController.createTransfer(...)`.

#### Scenario: Successful transfer produces exactly one balanced journal

- **WHEN** a transfer between two Active accounts with sufficient funds commits
- **THEN** exactly one new row exists in `journal_entry` with `verification_status = 'PENDING'`, exactly two new rows exist in `ledger_movement` linked to that journal (one with `movement_type = 'DEBIT'` for the source `account_id` and the requested amount, one with `movement_type = 'CREDIT'` for the destination `account_id` and the requested amount), and `sum of credit amounts equals sum of debit amounts` per F02's database-side balance check

#### Scenario: Failure mid-flight leaves no partial state

- **WHEN** a transfer rolls back because the journal-entry persistence step throws an exception (simulated in test by spying on `JournalEntries.save(...)`) after both account aggregates have already been mutated
- **THEN** both source and destination row balances are unchanged from their pre-transfer values, no row in `journal_entry` references the failed transfer, no orphaned `ledger_movement` rows persist, and the F07 paired locks have been released

#### Scenario: All four writes share the same transaction

- **WHEN** the use case runs to completion
- **THEN** the source UPDATE, destination UPDATE, journal_entry INSERT, and the two ledger_movement INSERTs all happen on the same JDBC connection within the single `@Transactional` proxy that wraps `TransferController.createTransfer(...)`

### Requirement: Journal description and status on creation

The journal entry produced by a transfer SHALL carry a description that names the source account number and the destination account number (format: `"Transfer from <source> to <destination>"`), a timestamp equal to the moment the use case minted the entry (sourced from an injectable `java.time.Clock` for test determinism), and `verification_status = 'PENDING'`. Promotion to `VERIFIED` is the responsibility of [[journal-verification]] (F10) — this change SHALL NOT introduce any verification logic.

#### Scenario: Journal description names both accounts

- **WHEN** a transfer from `ACC-001` to `ACC-002` commits
- **THEN** the persisted `journal_entry.description` is `"Transfer from ACC-001 to ACC-002"` (exact format) and `verification_status` is `'PENDING'`

#### Scenario: Journal timestamp comes from the injected Clock

- **WHEN** a `TransferFunds` is constructed with a fixed `Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC)` and a transfer commits
- **THEN** the persisted `journal_entry.entry_timestamp` equals `2026-05-24T10:00:00Z` exactly

#### Scenario: Production Clock defaults to systemUTC

- **WHEN** the application boots with the default profile
- **THEN** the Spring context contains a `Clock` bean whose value equals `Clock.systemUTC()` (registered as a `@Bean` in `BankCoreApplication`)

### Requirement: Use case acquires paired locks via F07 before loading aggregates

The `TransferFunds` use case SHALL acquire paired write locks on the source and destination `AccountNumber`s via the F07 `AccountLocker.withPairedLocks(...)` primitive *before* loading the aggregates from the `Accounts` port. Loading inside the locked region (lock-then-load) ensures the loaded aggregates cannot become stale relative to concurrent transfers between the same pair. The use case SHALL pass the source and destination `AccountNumber`s to the locker in their original (caller-supplied) order; the locker is responsible for canonicalising them. The use case SHALL perform the cheap same-account check *before* the lock acquisition so a self-transfer never touches the lock map.

#### Scenario: Lock acquired before findByNumber

- **WHEN** the use case runs (verified via a Mockito spy on `AccountLocker` and `Accounts` in the unit test)
- **THEN** `AccountLocker.withPairedLocks` is invoked before `Accounts.findByNumber` is invoked for either account

#### Scenario: Self-transfer short-circuits before lock acquisition

- **WHEN** the use case is called with `command.source().equals(command.destination())`
- **THEN** `SameAccountTransferException` is thrown immediately and `AccountLocker.withPairedLocks` is never invoked; the failure propagates as a `400 BAD_REQUEST_PAYLOAD` via the new F03 handler entry

#### Scenario: Caller argument order is preserved into the locker call

- **WHEN** the use case is called with `command = (source=ACC-A, destination=ACC-B)` and again with `command = (source=ACC-B, destination=ACC-A)`
- **THEN** the first call invokes `withPairedLocks(ACC-A, ACC-B, ...)`, the second call invokes `withPairedLocks(ACC-B, ACC-A, ...)`, and F07's canonical ordering guarantees both contend on the same first lock — the use case does not pre-sort

### Requirement: Transactional boundary lives on the controller method

The `TransferController.createTransfer(...)` method SHALL be annotated `@Transactional` so that the entire pipeline (DTO mapping, use case orchestration, paired-lock acquisition, aggregate mutation, journal persistence, lock release on `afterCompletion`) runs inside a single Spring-managed transaction. This SHALL be the *only* controller method in the codebase carrying `@Transactional`; F05's read controller delegates to its read-only adapter, and other controllers (OpenAPI, error test controllers) perform no persistence writes.

#### Scenario: Controller method declares @Transactional

- **WHEN** the production source of `com.bank.core.infrastructure.web.transfer.TransferController` is inspected
- **THEN** the `createTransfer(...)` method (or the class) is annotated `org.springframework.transaction.annotation.Transactional`, and the class-level Javadoc names this as the deliberate place for the transactional boundary

#### Scenario: TransferFunds use case stays Spring-free

- **WHEN** the production sources under `com.bank.core.application.transfer.` are inspected
- **THEN** they import nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and the existing F00 `applicationHasNoFrameworkDependencies` ArchUnit rule continues to pass; `TransferFunds` is registered as a Spring bean via a `@Bean` factory method in `BankCoreApplication`

### Requirement: SameAccountTransferException lives in the domain

`com.bank.core.domain.SameAccountTransferException` SHALL be a public class extending `DomainException` (the parent introduced by F01) carrying the offending `AccountNumber` as an accessor. The F03 `GlobalExceptionHandler` SHALL declare exactly one `@ExceptionHandler(SameAccountTransferException.class)` method returning `400 BAD_REQUEST_PAYLOAD`. The message SHALL identify the offending account number for support diagnostics.

#### Scenario: Exception type lives in domain and extends DomainException

- **WHEN** the production sources are inspected
- **THEN** `com.bank.core.domain.SameAccountTransferException` exists, extends `com.bank.core.domain.DomainException`, exposes an `account()` accessor of type `AccountNumber`, and is thrown by `TransferFunds.transfer(...)` when `command.source().equals(command.destination())`

#### Scenario: Handler maps to BAD_REQUEST_PAYLOAD

- **WHEN** the use case throws `SameAccountTransferException(AccountNumber.of("ACC-A"))`
- **THEN** the F03 handler returns HTTP 400 with body `{"code": "BAD_REQUEST_PAYLOAD", "message": "...ACC-A...", "timestamp": "..."}` and exactly those three fields
