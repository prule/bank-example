## 1. Domain Types and Exception

- [x] 1.1 Implement `LockAcquisitionTimeoutException` in `com.bank.core.domain` extending `DomainException` with diagnostic accessors `firstAccount`, `secondAccount`, and `waitMs`.

## 2. Decoupled Application Concurrency Port

- [x] 2.1 Define the plain Java `AccountLocker` interface in `com.bank.core.application.concurrency`.

## 3. Configuration Properties and Conditions

- [x] 3.1 Create `TransferLockingProperties` to bind properties `bank.transfer.locker` (normalized, must be 'jvm' or 'db') and `bank.transfer.lock-wait-ms` (default 5000).
- [x] 3.2 Update `application.yaml` and `application-test.yaml` to configure default locker strategy and timeout settings.

## 4. JVM-Based Locker Implementation

- [x] 4.1 Implement `JvmAccountLocker` in `com.bank.core.infrastructure.concurrency` utilizing `ConcurrentHashMap<AccountId, ReentrantLock>` and sequential lock acquisition in canonical order.
- [x] 4.2 Integrate Spring `TransactionSynchronization` to release JVM locks reliably after commit or rollback.
- [x] 4.3 Ensure `JvmAccountLocker` is gated by `@ConditionalOnProperty` and active by default.

## 5. DB-Based Row Locker Implementation

- [x] 5.1 Implement `DbAccountLocker` in `com.bank.core.infrastructure.concurrency` issuing a single parameter-bound SELECT FOR UPDATE query in canonical order.
- [x] 5.2 Configure database lock timeouts before query execution (H2/Postgres compatibility) and catch SQL exception codes to throw `LockAcquisitionTimeoutException`.
- [x] 5.3 Ensure `DbAccountLocker` is gated by `@ConditionalOnProperty`.

## 6. Verification and Validation

- [x] 6.1 Implement ArchUnit rule checks to verify no imports of `ReentrantLock` or `TransactionSynchronizationManager` outside the infrastructure concurrency package.
- [x] 6.2 Write unit and integration tests verifying canonical locking order, transaction-scoped lock lifecycles, and correctness under contention.
