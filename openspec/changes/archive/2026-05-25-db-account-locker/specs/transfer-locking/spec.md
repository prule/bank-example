## ADDED Requirements

### Requirement: Configurable locker strategy

The locking strategy SHALL be configurable via the Spring property `bank.transfer.locker` (default `jvm`). The accepted values SHALL be exactly `jvm` (case-insensitive) selecting `JvmAccountLocker` or `db` (case-insensitive) selecting `DbAccountLocker`. The `TransferLockingProperties` record SHALL normalise the value to lowercase and SHALL reject any other value at construction with `IllegalArgumentException("bank.transfer.locker must be 'jvm' or 'db' (was: '<value>')")`, failing the application context build. Exactly one of the two implementations SHALL be constructed per Spring context, gated by `@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "<jvm|db>")` on the respective `@Component`; `JvmAccountLocker` SHALL declare `matchIfMissing = true` so the default behaviour with no property set continues to be the JVM strategy.

#### Scenario: Default strategy is jvm

- **WHEN** the application boots with no `bank.transfer.locker` property set (e.g. default profile, no command-line override)
- **THEN** the Spring `ApplicationContext` contains exactly one `JvmAccountLocker` bean and zero `DbAccountLocker` beans; `context.getBean(AccountLocker.class)` returns the `JvmAccountLocker` instance

#### Scenario: Explicit jvm selects the JVM locker

- **WHEN** the application boots with `bank.transfer.locker=jvm` (case-insensitive — `JVM`, `Jvm`, `jvm` all accepted)
- **THEN** the same wiring as the default holds: exactly one `JvmAccountLocker`, zero `DbAccountLocker`

#### Scenario: Explicit db selects the DB locker

- **WHEN** the application boots with `bank.transfer.locker=db` (case-insensitive)
- **THEN** the Spring `ApplicationContext` contains exactly one `DbAccountLocker` bean and zero `JvmAccountLocker` beans; `context.getBean(AccountLocker.class)` returns the `DbAccountLocker` instance

#### Scenario: Invalid strategy fails context build

- **WHEN** the application boots with `bank.transfer.locker=hybrid` (or `redis`, `sql`, any string that is not `jvm` or `db`)
- **THEN** `TransferLockingProperties`'s compact constructor throws `IllegalArgumentException` with a message containing `bank.transfer.locker must be 'jvm' or 'db' (was: 'hybrid')`; the application context fails to start and Spring Boot's startup log surfaces the binding failure pointing at the property name

### Requirement: Database-backed locker uses SELECT FOR UPDATE in canonical order

When `bank.transfer.locker=db`, `DbAccountLocker.withPairedLocks(a, b, runnable)` SHALL acquire row-level exclusive locks on both accounts via a single SQL statement: `SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE` (or `IN (?)` for the same-account case after deduplication). The `ORDER BY account_number` clause SHALL ensure the rows are locked in canonical order within that single statement, so two concurrent calls — one with arguments `(A, B)` and one with arguments `(B, A)` — both lock `min(A, B)` first and then `max(A, B)`. The DB SHALL hold the row locks until the surrounding transaction commits or rolls back; no application-level release callback is needed because the database is the single arbiter. The lock-wait timeout (`bank.transfer.lock-wait-ms`) SHALL be honoured by setting the database's per-call lock-wait bound immediately before the FOR UPDATE statement (`SET LOCK_TIMEOUT <waitMs>` on H2; `SET LOCAL lock_timeout = '<waitMs>ms'` on PostgreSQL) and SHALL be reset to the database default (`SET LOCK_TIMEOUT 0` on H2; PostgreSQL's `SET LOCAL` auto-resets at commit) after the locks are acquired and before the runnable runs.

#### Scenario: One SQL statement acquires both locks in canonical order

- **WHEN** `DbAccountLocker.withPairedLocks(A, B, runnable)` is called inside an active transaction with two existing accounts
- **THEN** exactly one `SELECT ... FOR UPDATE` statement is executed during the call (verifiable by enabling Hibernate/JDBC SQL TRACE and counting occurrences of `SELECT id FROM account WHERE account_number IN`); the WHERE clause's `IN` list contains both `A.value()` and `B.value()`; the statement carries `ORDER BY account_number` so the row locks are acquired in canonical order

#### Scenario: Same-account call uses a single-element IN list

- **WHEN** `DbAccountLocker.withPairedLocks(A, A, runnable)` is called
- **THEN** the SQL's `IN` list contains exactly one entry, `A.value()`; the `SELECT ... FOR UPDATE` returns at most one row; the runnable runs exactly once

#### Scenario: DB locker releases via transaction commit, not afterCompletion hook

- **WHEN** `DbAccountLocker.withPairedLocks(A, B, runnable)` is called and the surrounding transaction commits
- **THEN** the row locks are released by the database at COMMIT (verifiable by a probe `SELECT ... FOR UPDATE` from a separate JDBC connection succeeding immediately after the application transaction commits); no `TransactionSynchronization.afterCompletion` callback is registered by `DbAccountLocker` (verifiable by inspecting the production source — `DbAccountLocker` does NOT call `TransactionSynchronizationManager.registerSynchronization(...)`)

#### Scenario: Lock-wait timeout maps to LockAcquisitionTimeoutException (PostgreSQL) or surfaces as a Spring/Hibernate rollback failure (H2)

- **WHEN** thread T1 holds the lock on account A inside an in-flight transaction, thread T2 calls `DbAccountLocker.withPairedLocks(A, B, ...)` with `bank.transfer.lock-wait-ms=200`, and T1 does not release the lock within 200 ms
- **THEN** T2's `SELECT ... FOR UPDATE` raises a JDBC exception whose SQLSTATE is `HYT00` (H2 statement timeout) / error code `50200` (H2 `LOCK_TIMEOUT_1`) or SQLSTATE `55P03` (PostgreSQL `lock_not_available`); the adapter catches it and throws `LockAcquisitionTimeoutException(min(A,B), max(A,B), 200)`. On PostgreSQL the exception propagates unchanged to the caller. On H2 the surrounding Spring rollback subsequently fails (Hibernate raises `JpaSystemException("Unable to rollback against JDBC Connection")` and Spring's `TransactionTemplate.rollbackOnException` replaces the original `LockAcquisitionTimeoutException` with the rollback failure because the rollback failure is not a `TransactionSystemException`), so callers running under H2 observe a `JpaSystemException` instead. The contention IS detected and the transfer IS rolled back in both cases; only the exception class surfaced to the caller differs. T2 holds no lock when either exception propagates; T1 is unaffected. The H2 behaviour is a known limitation tracked for a follow-up change (which will either preserve the `LockAcquisitionTimeoutException` via a manual connection rollback in the locker's catch block, or update F03's `GlobalExceptionHandler` to walk the cause chain).

#### Scenario: Missing account is transparent to the locker

- **WHEN** `DbAccountLocker.withPairedLocks(EXISTING, MISSING-NEVER-CREATED, runnable)` is called and only `EXISTING` is a row in the `account` table
- **THEN** the `SELECT ... FOR UPDATE` returns exactly one row (the row for `EXISTING`); no exception is thrown by the locker itself; the runnable runs to completion; downstream use cases that load `MISSING-NEVER-CREATED` and find it absent are responsible for their own exception handling (the locker's contract is to serialise, not to enforce existence)

### Requirement: DB locker honours the cross-instance correctness story

The `DbAccountLocker` SHALL serve as the canonical lock arbiter across all application instances connected to the same database. Two JVMs running with `bank.transfer.locker=db` and pointing at the same database SHALL contend for the same row-level locks; their canonical ordering rule SHALL be identical (the `ORDER BY account_number` clause inside the single SQL statement), so two opposite-direction transfers initiated on two different JVMs SHALL serialise without deadlock or balance corruption.

#### Scenario: Multi-instance correctness is structurally guaranteed

- **WHEN** the production source of `DbAccountLocker` is inspected
- **THEN** the lock-acquisition SQL is parameterised on `account_number` (a column whose value is identical across all JVMs reading the same row); the canonical-order clause (`ORDER BY account_number`) is part of the SQL string (not a JVM-local data structure); no in-memory `Map`, `Set`, or `Lock` is used to track acquired locks (verifiable by `grep -E 'ConcurrentHashMap|HashMap|Lock|Semaphore' DbAccountLocker.java` returning zero matches); the multi-instance correctness story therefore reduces to "every JVM issues the same SQL against the same DB" — which is the canonical multi-instance correctness guarantee a row-locking database provides

## MODIFIED Requirements

### Requirement: Single lock-acquisition component

The canonical lock-acquisition rule SHALL be enforced by exactly one shared component active per Spring context (`AccountLocker` port in `com.bank.core.application.concurrency` implemented by either `JvmAccountLocker` or `DbAccountLocker` in `com.bank.core.infrastructure.concurrency`, selected by `bank.transfer.locker` per the "Configurable locker strategy" requirement). No production class outside `com.bank.core.infrastructure.concurrency` SHALL import `java.util.concurrent.locks.ReentrantLock` or `org.springframework.transaction.support.TransactionSynchronizationManager`.

#### Scenario: One source of truth for paired locks

- **WHEN** the ArchUnit `ModuleBoundaryTest` runs over the production sources
- **THEN** no class outside `com.bank.core.infrastructure.concurrency..` depends on `java.util.concurrent.locks.ReentrantLock`, and no class outside `com.bank.core.infrastructure.concurrency..` depends on `org.springframework.transaction.support.TransactionSynchronizationManager`; the only paired-lock acquirers the build allows are `JvmAccountLocker` and `DbAccountLocker`

#### Scenario: Port is plain Java in application

- **WHEN** the production sources of `com.bank.core.application.concurrency.AccountLocker` are inspected
- **THEN** the interface declares no annotation other than JDK-standard ones, imports nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and the existing F00 ArchUnit `applicationHasNoFrameworkDependencies` rule continues to pass

#### Scenario: Exactly one locker bean is constructed at runtime

- **WHEN** the application context is inspected via `context.getBeansOfType(AccountLocker.class)`
- **THEN** the returned map has size exactly 1; its single value is either a `JvmAccountLocker` or a `DbAccountLocker` instance depending on the `bank.transfer.locker` property (or the JVM default when unset); the other implementation's `@ConditionalOnProperty` evaluated to false and the bean was never instantiated
