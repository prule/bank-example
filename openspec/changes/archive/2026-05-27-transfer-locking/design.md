## Context

Fund transfers between bank accounts require atomic updates to the balances of two different accounts. To prevent concurrency conflicts, race conditions, and balance corruption, the transfer path must acquire exclusive locks on both accounts before executing the transfer. 

However, acquiring locks on two resources concurrently introduces a classic deadlock risk: if thread 1 attempts to transfer from A to B (acquiring lock A, then lock B) while thread 2 attempts to transfer from B to A (acquiring lock B, then lock A) concurrently, both threads can deadlock waiting for the other. This capability implements a deadlock-free concurrent locking strategy, supporting both JVM-based (in-memory) and DB-based (row-level) exclusion.

## Goals / Non-Goals

**Goals:**
- **Decoupled Concurrency Port**: Define the plain Java `AccountLocker` interface in `com.bank.core.application.concurrency`.
- **Canonical Lock Ordering**: Enforce deterministic locking order by account number string (`String.compareTo`) to eliminate deadlock risks structurally.
- **Transaction-Scoped JVM Locking**: Implement `JvmAccountLocker` using a shared mapping of `ReentrantLock` instances, registering a Spring `TransactionSynchronization` callback at acquisition time to release locks upon commit/rollback.
- **Pessimistic DB-Row Locking**: Implement `DbAccountLocker` utilizing a single database-side `SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE` statement to acquire row locks in canonical order.
- **Configurable Strategy**: Introduce `TransferLockingProperties` to validate the `bank.transfer.locker` strategy (`jvm` or `db`) and context-switch implementations transparently using `@ConditionalOnProperty`.
- **Boundary Verification**: Ensure structural boundaries are verified using ArchUnit tests.

**Non-Goals:**
- Implementing the actual fund transfer execution flow itself (which belongs in the subsequent `fund-transfer` capability).
- Providing multi-JVM distributed locking using Redis or external coordination engines (DB-based locking natively provides cross-instance safety, and JVM-based locking is for single-instance setups).

## Decisions

### 1. Account Locker Interface (Port)
We define a plain-Java, Spring-free port in the application module:
```java
package com.bank.core.application.concurrency;

import com.bank.core.domain.AccountId;

public interface AccountLocker {
    void withPairedLocks(AccountId a, AccountId b, Runnable action);
    long getWaitMs();
}
```
*Rationale*: Isolates the business layer from infrastructure locking primitives (like Spring `TransactionSynchronizationManager` or JDBC connections).

### 2. Canonical Ordering by Account Number
To prevent deadlocks, the lock acquisition path always determines the lower and higher account number strings first. Let $A$ and $B$ be two accounts:
- If $A.value() < B.value()$, locks are acquired in sequence: $A$, then $B$.
- If $A.value() > B.value()$, locks are acquired in sequence: $B$, then $A$.
- If $A.value() = B.value()$, a single lock is acquired once.

*Rationale*: Guarantees a total ordering of locks, structurally preventing deadlock cycles regardless of caller parameter invocation order.

### 3. JVM-Based Locker (`JvmAccountLocker`)
- Uses a `ConcurrentHashMap<AccountId, ReentrantLock>` to cache locks per account.
- Before locking, validates that an active transaction exists via `TransactionSynchronizationManager.isActualTransactionActive()`.
- Locks are acquired sequentially with a wait limit `bank.transfer.lock-wait-ms` using `tryLock(timeout, TimeUnit.MILLISECONDS)`.
- Registers a `TransactionSynchronization` callback using `TransactionSynchronizationManager.registerSynchronization` whose `afterCompletion` hook unlocks the locked accounts.

*Rationale*: Keeps thread locks bound to database transaction lifecycles in single-instance JVM environments.

### 4. Database-Row Pessimistic Locker (`DbAccountLocker`)
- Executes a single, parameter-bound query:
  ```sql
  SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE
  ```
- To support timeouts under different databases:
  - For H2 (tests): Executes `SET LOCK_TIMEOUT <timeoutMs>` prior to the query, and resets it to a standard default (`1000`ms) afterwards.
  - Catches JDBC/JPA exceptions containing specific SQLSTATEs (`HYT00`, `50200`, `55P03`) to translate them into `LockAcquisitionTimeoutException`.

*Rationale*: Relies on the database's transaction manager as the single source of truth, guaranteeing correct locking even across multiple active JVM instances connected to the same DB.

### 5. Domain Exception mapping
- The `LockAcquisitionTimeoutException` is defined in `com.bank.core.domain` and inherits from `DomainException`. It carries the two `AccountNumber`s in canonical order and the wait budget.

*Rationale*: Enables clean translation at the HTTP boundary in the global exception handler without coupling to specific persistence/locking details.

## Risks / Trade-offs

- **[Risk] JVM Lock Leakage**: If a thread crashes or leaves the JVM locking path without completing the transaction synchronization, locks could leak.
  - *Mitigation*: The `afterCompletion` hook runs reliably in Spring's transaction flow during commit/rollback, which is covered extensively in integration tests.
- **[Risk] H2 Statement Rollback Failure**: Under H2, a statement-level lock timeout triggers a rollback failure exception when Spring tries to clean up.
  - *Mitigation*: We document and assert this behavior under H2, acknowledging it as an environment constraint. On production Postgres, it rolls back gracefully and propagates the mapped exception.
