# Transfer Locking

## Purpose

Concurrency contract for the transfer path. Two transfers that touch the same pair of accounts in opposite directions must never deadlock, must serialise into a defined order, and must never produce incorrect balances under contention.

## Requirements

### Requirement: Canonical lock order by account number

Before mutating two accounts in one transfer, the operation SHALL acquire an exclusive write lock on each account. Locks SHALL be acquired in a canonical order determined purely by a deterministic comparison of the two `AccountNumber` string values (lower account number first by `String.compareTo`), and never by the order in which the caller provided them. When both arguments refer to the same account (`a.equals(b)`), the locker SHALL acquire the single lock once and proceed.

#### Scenario: Lock order is independent of caller argument order

- **WHEN** thread T1 calls `AccountLocker.withPairedLocks(A, B, ...)` and thread T2 calls `AccountLocker.withPairedLocks(B, A, ...)` concurrently
- **THEN** both threads attempt to acquire the lock on `min(A.value(), B.value())` first and then the lock on `max(A.value(), B.value())`, observable via a probe that records the sequence of `ReentrantLock.tryLock` calls in the adapter

#### Scenario: Counter-direction concurrent transfers never deadlock

- **WHEN** 100 concurrent calls to `AccountLocker.withPairedLocks` fire against the same pair `(A, B)`, half with arguments `(A, B)` and half with `(B, A)`, each performing an `AtomicLong` increment on a synthetic balance map while holding the locks
- **THEN** every call completes within 10 seconds, zero `LockAcquisitionTimeoutException` is thrown, and the two synthetic balances exactly equal their starting values

#### Scenario: Same-account call locks once

- **WHEN** `AccountLocker.withPairedLocks(A, A, runnable)` is called
- **THEN** the adapter acquires the lock on A exactly once (verified by `ReentrantLock.getHoldCount()` returning 1 inside the runnable), executes the runnable, and releases the lock at transaction completion

### Requirement: Locks span the surrounding transaction

The locks acquired by a transfer SHALL be held for the full duration of the surrounding transaction and SHALL be released only on commit or rollback via a `TransactionSynchronization.afterCompletion` callback registered at acquisition time. A transfer that cannot acquire a lock within `bank.transfer.lock-wait-ms` (default `5000`, test profile `500`) SHALL throw `LockAcquisitionTimeoutException` carrying the two `AccountNumber`s and the wait bound in milliseconds. A call made outside an active transaction SHALL throw `IllegalStateException` before any lock is acquired.

#### Scenario: Lock released on commit

- **WHEN** a `@Transactional` use case calls `AccountLocker.withPairedLocks(A, B, runnable)` and its surrounding transaction commits
- **THEN** the locks on A and B are released after commit and a subsequent caller acquires them without waiting

#### Scenario: Lock released on rollback

- **WHEN** a `@Transactional` use case calls `AccountLocker.withPairedLocks(A, B, runnable)` and the runnable (or surrounding transaction) throws, causing rollback
- **THEN** the locks on A and B are released and immediately available to other callers; no lock is leaked across the failure

#### Scenario: Unrelated accounts proceed without waiting

- **WHEN** thread T1 holds locks on accounts A and B inside an in-flight transaction, and thread T2 calls `AccountLocker.withPairedLocks(C, D, ...)` for an unrelated pair
- **THEN** T2's acquisition completes in under 200 ms — without waiting on T1 — and T2's runnable runs to completion before T1 commits

#### Scenario: Timeout becomes LockAcquisitionTimeoutException

- **WHEN** thread T1 holds the lock on `A` and thread T2 calls `AccountLocker.withPairedLocks(A, B, ...)` with `bank.transfer.lock-wait-ms` set to a value that elapses before T1 releases
- **THEN** T2 throws `LockAcquisitionTimeoutException` carrying both account numbers and the wait bound, T2 does not hold any lock when the exception propagates, and T1 is unaffected

#### Scenario: Call outside a transaction is rejected

- **WHEN** `AccountLocker.withPairedLocks(A, B, runnable)` is called without an active transaction (no `@Transactional` proxy on the stack and no programmatic transaction template wrapping the call)
- **THEN** the call throws `IllegalStateException` with a message identifying the missing transaction, no lock is acquired, and the runnable is not executed

### Requirement: Single lock-acquisition component

The canonical lock-acquisition rule SHALL be enforced by exactly one shared component (`AccountLocker` port in `com.bank.core.application.concurrency` implemented by `JvmAccountLocker` in `com.bank.core.infrastructure.concurrency`) used by every code path that mutates two accounts simultaneously. No production class outside `com.bank.core.infrastructure.concurrency` SHALL import `java.util.concurrent.locks.ReentrantLock` or `org.springframework.transaction.support.TransactionSynchronizationManager`.

#### Scenario: One source of truth for paired locks

- **WHEN** the ArchUnit `ModuleBoundaryTest` runs over the production sources
- **THEN** no class outside `com.bank.core.infrastructure.concurrency..` depends on `java.util.concurrent.locks.ReentrantLock`, and no class outside `com.bank.core.infrastructure.concurrency..` depends on `org.springframework.transaction.support.TransactionSynchronizationManager`; the only paired-lock acquirer the build allows is `JvmAccountLocker`

#### Scenario: Port is plain Java in application

- **WHEN** the production sources of `com.bank.core.application.concurrency.AccountLocker` are inspected
- **THEN** the interface declares no annotation other than JDK-standard ones, imports nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and the existing F00 ArchUnit `applicationHasNoFrameworkDependencies` rule continues to pass

### Requirement: Correctness under cross-pair contention

The system SHALL remain correct when a single account is the source for one transfer and the destination for another running simultaneously. The locker SHALL serialise all calls that share at least one account, even when the second account differs.

#### Scenario: Cross-pair correctness

- **WHEN** call C1 acquires paired locks on `(A, B)` while call C2 simultaneously requests paired locks on `(C, A)`, each performing an `AtomicLong` adjustment on a synthetic balance map keyed by `AccountNumber`
- **THEN** both calls commit in some serial order, no deadlock occurs, no `LockAcquisitionTimeoutException` is thrown, and account A's final synthetic balance is the deterministic sum of the two adjustments

### Requirement: Configurable wait timeout

The lock acquisition wait bound SHALL be externalised as the Spring configuration property `bank.transfer.lock-wait-ms`. The default value SHALL be `5000` milliseconds in production (`application.yaml`). The test profile (`application-test.yaml`) SHALL override the default to `500` milliseconds so contention tests fail fast rather than hanging the suite. The adapter SHALL read the property once at construction via `@ConfigurationProperties` and expose it for diagnostic logging.

#### Scenario: Default production wait bound is 5 seconds

- **WHEN** the application boots with the default profile and the adapter is inspected via the Spring context
- **THEN** `JvmAccountLocker` reports a `waitMs` of `5000`

#### Scenario: Test profile overrides to 500ms

- **WHEN** the integration test suite boots with the `test` profile and the adapter is inspected
- **THEN** `JvmAccountLocker` reports a `waitMs` of `500`, so a thread waiting on a contended lock fails within 500 ms rather than the production budget

### Requirement: Timeout exception lives in the domain module

`LockAcquisitionTimeoutException` SHALL be a public class in `com.bank.core.domain` extending `DomainException` (the parent introduced by F01), so the F03 global exception handler can map it at the HTTP boundary in a future change without the `infrastructure.web` package importing anything from `com.bank.core.infrastructure.concurrency`. The exception SHALL carry the two `AccountNumber` values and the wait bound (`long waitMs`) as accessors.

#### Scenario: Exception type lives in domain and extends DomainException

- **WHEN** the production sources are inspected
- **THEN** `com.bank.core.domain.LockAcquisitionTimeoutException` exists, extends `com.bank.core.domain.DomainException`, exposes `firstAccount()`, `secondAccount()`, and `waitMs()` accessors, and is imported by `JvmAccountLocker` (the only class that throws it)

#### Scenario: Exception carries diagnostic context

- **WHEN** `LockAcquisitionTimeoutException` is thrown after a timeout while waiting on the second of a pair
- **THEN** the instance's `firstAccount()` and `secondAccount()` return the two `AccountNumber` arguments to the original `withPairedLocks` call in canonical order, and `waitMs()` returns the bound that elapsed
