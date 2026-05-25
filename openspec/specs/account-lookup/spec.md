# Account Lookup

## Purpose

Read-only HTTP endpoint that returns the current state of a single account, addressed by its account number. The endpoint is idempotent and never mutates state. Also lands the foundational account persistence (table, JPA entity, repository, mapper, adapter, application port) consumed by F06 (fund transfer), F08 (account opening), F09 (dev data seeding), and F11 (balance drift detection).

## Requirements

### Requirement: GET endpoint returns account state

The service SHALL expose `GET /api/v1/accounts/{accountNumber}` returning HTTP `200` with a body containing exactly the fields `accountNumber` (string), `balance` (string, decimal with exactly two fraction digits, matching the regex `^\d+\.\d{2}$`), and `status` (string, one of `ACTIVE`, `SUSPENDED`, `CLOSED`). The response body SHALL match the `AccountResponse` schema declared in the OpenAPI contract exactly — no extra fields, no missing required fields. The endpoint is generated from the OpenAPI contract; the controller SHALL implement the generated `AccountsApi.lookupAccount(...)` interface and SHALL NOT define its own method signature.

#### Scenario: Existing account returns 200 with current state

- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` for an account whose persisted balance is `100.00` and status is `ACTIVE`
- **THEN** the response is HTTP 200, content-type `application/json`, and the body has exactly three JSON keys (`accountNumber`, `balance`, `status`) with values matching the persisted state (`balance` serialised as `"100.00"`, status serialised as `"ACTIVE"`)

#### Scenario: Balance reflects committed transfers immediately

- **WHEN** a `@Transactional` write commits a new balance for account A via the `Accounts` port (e.g. a test simulating a future fund-transfer use case), then a client immediately reads A
- **THEN** the returned balance equals the post-commit value with no staleness window; the adapter does not cache loaded aggregates

#### Scenario: Read is idempotent

- **WHEN** a client requests the same `GET /api/v1/accounts/{accountNumber}` three times within a single committed state
- **THEN** all three response bodies are byte-for-byte identical and all return HTTP 200

#### Scenario: Body shape matches the OpenAPI schema

- **WHEN** the served OpenAPI document at `/v3/api-docs` is inspected
- **THEN** it declares a `GET /api/v1/accounts/{accountNumber}` operation with `operationId: lookupAccount`, a path parameter `accountNumber` of type string, and a 200 response referencing the `AccountResponse` schema whose required fields are `accountNumber`, `balance`, `status`

### Requirement: Missing account returns 404 RESOURCE_NOT_FOUND

A request for an account that does not exist SHALL return HTTP `404` with the canonical error envelope (per [[api-error-contract]]): exactly three fields `code`, `message`, `timestamp`. `code` SHALL be `RESOURCE_NOT_FOUND`. `message` SHALL be human-readable and SHALL name the missing account number for support diagnostics. No partial response, no leaked stack trace, no extra fields. The lookup SHALL log at INFO level on every 404 so operators can monitor for enumeration attempts.

#### Scenario: Unknown account returns RESOURCE_NOT_FOUND

- **WHEN** a client requests `GET /api/v1/accounts/UNKNOWN-123` and no account with that number exists
- **THEN** the response is HTTP 404 with body fields `code = "RESOURCE_NOT_FOUND"`, a `message` referencing `"UNKNOWN-123"`, and a `timestamp` in ISO-8601-with-offset format; the body has exactly those three fields

#### Scenario: 404 is logged at INFO with the missing number

- **WHEN** a 404 lookup occurs
- **THEN** the application log records one INFO line naming the missing account number, suitable for an alert that counts 404s per source over time

#### Scenario: Lookup throws ResourceNotFoundException, handler maps it

- **WHEN** the controller's lookup returns `Optional.empty()` from `Accounts.findByNumber(...)`
- **THEN** the controller throws `com.bank.core.domain.ResourceNotFoundException("account", number.value())`, the F03 `GlobalExceptionHandler`'s `@ExceptionHandler(ResourceNotFoundException.class)` method catches it, and the response envelope is produced by that single handler entry (no other class fabricates 404 envelopes for this endpoint)

### Requirement: Status enum covers every returnable status

The `AccountResponse.status` enum in the OpenAPI contract SHALL include every value the lookup could ever return for a non-deleted account: `ACTIVE`, `SUSPENDED`, `CLOSED`. The enum SHALL match the case used by `com.bank.core.domain.AccountStatus` Java enum constants so the mapper is a direct `name()` conversion with no case translation. This requirement closes the `account-status-enum-coverage` open decision in [openspec/config.yaml](openspec/config.yaml).

#### Scenario: Closed account is representable

- **WHEN** an account whose persisted status is `CLOSED` is read via `GET /api/v1/accounts/{accountNumber}`
- **THEN** the response is HTTP 200 with `status: "CLOSED"`, the body validates against the published `AccountResponse` schema, and the endpoint does not return 500 because of a missing enum value

#### Scenario: Suspended account is representable

- **WHEN** an account whose persisted status is `SUSPENDED` is read via the lookup endpoint
- **THEN** the response is HTTP 200 with `status: "SUSPENDED"`

#### Scenario: OpenAPI enum lists exactly the three statuses

- **WHEN** the published OpenAPI document is inspected
- **THEN** the `AccountResponse.status` property declares `enum: [ACTIVE, SUSPENDED, CLOSED]` (in that or any order) with no other values

### Requirement: Endpoint is read-only

The endpoint SHALL NOT mutate any state. No row in `account` SHALL be modified, no row in `journal_entry` or `ledger_movement` SHALL be created or modified, and no caching pathway SHALL retain a copy that could diverge from the committed state. The adapter method backing the lookup SHALL be `@Transactional(readOnly = true)` so Hibernate's dirty-checking does not persist accidental mutations on commit.

#### Scenario: Lookup does not write to the ledger or the account table

- **WHEN** a client requests `GET /api/v1/accounts/{accountNumber}` (whether the account exists or not)
- **THEN** the row counts of `journal_entry`, `ledger_movement`, and `account` are identical before and after the request, and the stored balance/status of the looked-up account is unchanged

#### Scenario: Adapter is read-only at the JPA boundary

- **WHEN** the production source of `com.bank.core.infrastructure.persistence.account.AccountsJpaAdapter` is inspected
- **THEN** `findByNumber(...)` is annotated `@Transactional(readOnly = true)`, and the method does not invoke `EntityManager.flush()`, `repository.save(...)`, or any `@Modifying` query

### Requirement: Account persistence introduced

This capability SHALL introduce the `account` table via Flyway migration `V3__account.sql`, the `AccountEntity` JPA class under `com.bank.core.infrastructure.persistence.account`, and the `Accounts` application port under `com.bank.core.application.account`. The table SHALL declare `id` (UUID primary key), `account_number` (VARCHAR(64) NOT NULL UNIQUE), `balance` (NUMERIC(19,2) NOT NULL with `CHECK (balance >= 0)`), and `status` (VARCHAR(16) NOT NULL with `CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))`). A unique index SHALL enforce account-number uniqueness so concurrent `save` calls cannot create duplicates.

#### Scenario: Schema is created by Flyway

- **WHEN** the application boots in any profile that runs Flyway (default or test)
- **THEN** migration `V3__account.sql` runs, the `account` table exists with the four columns and two CHECK constraints, and a unique index `idx_account_account_number` covers `account_number`

#### Scenario: ddl-auto=validate confirms entity matches schema

- **WHEN** the application boots with `spring.jpa.hibernate.ddl-auto=validate` (F00 default)
- **THEN** Hibernate's validation of `AccountEntity` against the V3 schema passes; mismatched column types or names fail boot

#### Scenario: Duplicate account_number is rejected

- **WHEN** two `Accounts.save(...)` calls in separate transactions persist different `Account` aggregates with the same `AccountNumber`
- **THEN** the second commit fails with a `DataIntegrityViolationException` and the unique index `idx_account_account_number` is the reason

### Requirement: Application port stays Spring-free

The `Accounts` interface in `com.bank.core.application.account` SHALL be a plain Java interface with no Spring/JPA annotations and no imports from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`. Its method signatures SHALL use only domain types (`Account`, `AccountNumber`, `AccountId`) and JDK types (`Optional`). The port SHALL declare:

- `Optional<Account> findByNumber(AccountNumber number)` — public lookup by external account number (consumed by the HTTP read endpoint and by [[fund-transfer]] / [[account-opening]] / [[dev-data-seeding]]).
- `Optional<Account> findById(AccountId id)` — internal lookup by aggregate id (consumed by [[journal-verification]]'s suspend cascade, which gets `AccountId`s from `Movement` records rather than account numbers). NOT exposed through the HTTP surface; internal-only.
- `Account save(Account account)` — upsert by aggregate id (consumed by [[fund-transfer]] / [[account-opening]] / [[dev-data-seeding]] / [[journal-verification]]).

#### Scenario: Port is plain Java

- **WHEN** the production source of `com.bank.core.application.account.Accounts` is inspected
- **THEN** the interface declares no annotation other than JDK-standard ones, imports nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and the existing F00 ArchUnit `applicationHasNoFrameworkDependencies` rule continues to pass

#### Scenario: Save method ships now even though the lookup does not call it

- **WHEN** the `Accounts` port is inspected
- **THEN** it declares both `findByNumber` and `save` so that F06 (fund transfer), F08 (account opening), and F09 (dev data seeding) can land without further port edits

#### Scenario: findById is available for internal id-based loads

- **WHEN** the `Accounts` port is inspected
- **THEN** it declares `Optional<Account> findById(AccountId id)`; the infrastructure adapter implements it by delegating to the existing `JpaRepository.findById(UUID)` with no new SQL and no schema change; the method is NOT reached from any HTTP controller (verified by a grep across `com.bank.core.infrastructure.web..` returning zero references to `Accounts.findById(...)`)

#### Scenario: findById returns empty for an unknown id

- **WHEN** `Accounts.findById(AccountId.of(UUID.randomUUID()))` is called with a UUID that maps to no row
- **THEN** the call returns `Optional.empty()`; no exception is thrown; the call does not log

### Requirement: Account aggregate exposes a rehydrate factory for mapping

The pure-domain `Account` class SHALL expose a `public static rehydrate(AccountId id, AccountNumber number, Money balance, AccountStatus status)` factory that constructs an aggregate without re-running the `open(...)` factory's invariants (no fresh id minting, no forced `ACTIVE` status). Class-level Javadoc SHALL document that the factory is for the persistence mapper only and SHALL NOT be called by application or controller code.

#### Scenario: rehydrate preserves persisted state

- **WHEN** `Account.rehydrate(id, number, balance, status)` is called with non-null arguments
- **THEN** the returned aggregate's `id()`, `number()`, `balance()`, and `status()` accessors return the supplied values without modification; no `AccountId.generate()` call occurs and the status is not forced to `ACTIVE`

#### Scenario: rehydrate rejects null arguments

- **WHEN** `Account.rehydrate(null, number, balance, status)` (or any other null argument combination) is called
- **THEN** the call throws `NullPointerException` with a message identifying the null parameter

#### Scenario: Class-level Javadoc documents the mapper-only contract

- **WHEN** the `Account.java` source is inspected
- **THEN** the class-level Javadoc names `com.bank.core.infrastructure.persistence.account.AccountMapper` as the only legitimate caller of `rehydrate(...)` and warns application/controller code not to invoke it

### Requirement: ResourceNotFoundException lives in the domain

`com.bank.core.domain.ResourceNotFoundException` SHALL be a public class extending `DomainException` (the parent introduced by F01) carrying a `String resourceType` (e.g. `"account"`) and a `String identifier` (e.g. the offending account number) as accessors. The F03 `GlobalExceptionHandler` SHALL declare exactly one `@ExceptionHandler(ResourceNotFoundException.class)` method that returns HTTP `404` with `code = RESOURCE_NOT_FOUND`. The exception type SHALL be reusable by future capabilities that need a 404 (e.g. F10's missing journal id, or any future "missing X" surface).

#### Scenario: Exception type lives in domain and extends DomainException

- **WHEN** the production sources are inspected
- **THEN** `com.bank.core.domain.ResourceNotFoundException` exists, extends `com.bank.core.domain.DomainException`, exposes `resourceType()` and `identifier()` accessors, and is thrown by `AccountController.lookupAccount(...)` when the port returns `Optional.empty()`

#### Scenario: Exception is mapped by the global handler

- **WHEN** the controller throws `ResourceNotFoundException("account", "X-001")`
- **THEN** the F03 `GlobalExceptionHandler`'s `@ExceptionHandler(ResourceNotFoundException.class)` produces an HTTP 404 response with body `{"code": "RESOURCE_NOT_FOUND", "message": "...X-001...", "timestamp": "..."}` and exactly those three fields

#### Scenario: Handler reuses the same mapping for every resource type

- **WHEN** a different capability (future) throws `ResourceNotFoundException("journal", id)` for a missing journal
- **THEN** the same `GlobalExceptionHandler` entry produces a 404 with `code = RESOURCE_NOT_FOUND` and a message naming `"journal"` and the id; no per-resource-type handler entry is needed
