## Why

Currently, concurrent fund transfers run the risk of deadlocks, race conditions, and inconsistent balances under high contention. Introducing a concurrency control capability ensures that transfers acquire exclusive write locks on both involved accounts in a strict, deterministic canonical order, guaranteeing thread safety, preventing deadlocks, and maintaining balance integrity.

## What Changes

- **Canonical Lock Order**: Implement logic to acquire exclusive locks on paired accounts in a deterministic, account-number sorted order (`min(A, B)` then `max(A, B)`) to guarantee deadlock-free execution under concurrent counter-direction transfers.
- **Transaction-Scoped Locks**: Ensure locks are held for the full duration of a Spring `@Transactional` context and released safely on commit or rollback via `TransactionSynchronization` hooks.
- **Configurable Locker Strategy**: Support a configurable locking strategy via property `bank.transfer.locker` (with options `jvm` or `db`), mapping to distinct `JvmAccountLocker` (in-memory) and `DbAccountLocker` (database row-level lock via `SELECT FOR UPDATE`) implementations.
- **Lock Acquisition Timeout**: Enforce configurable timeout via `bank.transfer.lock-wait-ms`, throwing a domain-level `LockAcquisitionTimeoutException` carrying the diagnostic context upon failure to acquire locks.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `transfer-locking`: Implement canonical lock ordering by account number, transaction-scoped lock lifecycle, configurable JVM/DB locking strategies, lock-wait timeout configuration, and database-level `SELECT ... FOR UPDATE` canonical query locking.

## Impact

- **Concurrency Security**: Mutex control is established across all concurrent transfer requests touching overlapping accounts, preventing deadlocks.
- **Spring Integration**: Lockers will deeply integrate with Spring's `TransactionSynchronizationManager` to release locks upon transaction completion.
- **Configuration Defaults**: New properties (`bank.transfer.locker`, `bank.transfer.lock-wait-ms`) are introduced with standard defaults (`jvm` and `5000`ms respectively, overridden to `500`ms in testing).
