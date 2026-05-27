## Why

Currently, the system lacks the capability to transfer funds between accounts. While we have the account domain models, ledger entry structures, and locking capabilities, there is no high-level transactional service or API endpoint to coordinate an atomic double-entry balance movement from one active account to another. Implementing `fund-transfer` delivers a secure, atomic, and spec-compliant REST endpoint (`POST /api/v1/transfers`) to execute and record concurrent transfers.

## What Changes

- **Transactional REST Endpoint**: Implement `TransfersApi.createTransfer(...)` (generated from OpenAPI) on `TransferController`, returning HTTP `204 No Content` on success.
- **Transactional Orchestration Boundary**: Wrap the controller method with Spring's `@Transactional` to ensure the entire operation (paired lock acquisition, aggregate mutations, journal persistence, and lock release) executes atomically on a single database connection.
- **Spring-Free Application Use Case**: Implement the `TransferFunds` use case in the application layer (`com.bank.core.application.transfer`) coordinating business execution without framework dependencies.
- **Domain same-account validation**: Add domain-level `SameAccountTransferException` extending `DomainException` to reject self-transfers before acquiring locks.
- **Pessimistic Contention Safety**: Use `AccountLocker` to acquire paired locks on both account numbers (in their original calling order) before fetching aggregates, ensuring deadlock-free concurrent executions.
- **Double-Entry Journal & Ledger Entries**: Generate exactly one `PENDING` double-entry `JournalEntry` with two movements (`DEBIT` on source, `CREDIT` on destination) matching the transfer amount, using an injectable `Clock` for timestamps.
- **Standardized Error Handling**: Add INFO-level handlers in `GlobalExceptionHandler` mapping domain exceptions (`AccountInactiveException`, `InsufficientFundsException`, and `SameAccountTransferException`) to `ACCOUNT_INACTIVE`, `INSUFFICIENT_FUNDS`, and `BAD_REQUEST_PAYLOAD` respectively.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `fund-transfer`: Implement atomic balance transfers, double-entry immutable ledger creation, pessimistic paired-locking integration, transaction boundary configuration on the controller, payload/business validation, and global exception mappings.

## Impact

- **API Interface**: Active implementation of `POST /api/v1/transfers` conforming to the OpenAPI contract.
- **Ledger Invariant Enforcement**: Ensures every successful balance transfer corresponds to a validated, balance-checked ledger movement.
- **Concurrency & Contention**: Protects account states under heavy concurrent load using canonical deadlock-free locks.
- **Downstream Capabilities**: Provides the core transactional infrastructure required for `account-opening` (which funds accounts via transfer from a clearing account) and background audit capabilities (`journal-verification` and `balance-drift-detection`).
