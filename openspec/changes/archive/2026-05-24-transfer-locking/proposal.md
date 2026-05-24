## Why

F01 (`Account` aggregate) and F02 (`JournalEntry`/`Movement`, ledger adapter) have shipped. The capability slot `[F07]` sits between `[F01, F02]` and `[F05, F06]` in the manifest's build order: every transfer in F06 must acquire exclusive locks on its two accounts in a canonical order, and F07 is the single component that owns that rule. Shipping F07 *before* F06 means F06's use case can be written as "acquire paired locks via the F07 component, then debit/credit, then save the journal" without ever inventing ad-hoc locking on the side. The published REQUIREMENTS doc names "Two concurrent transfers between the same pair of accounts in opposite directions must never deadlock" as a core promise — F07 makes that promise mechanically enforceable.

This change introduces the locking primitive (`AccountLocker`) plus the contract that it is the *only* place in the codebase that acquires write locks on accounts, plus the stress test that proves the deadlock-free property under load.

## What Changes

- Introduce the `AccountLocker` component in `application` as a plain Java port: `void withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction)`. The port enforces canonical ordering: it sorts the two `AccountNumber` arguments by their `String` value (lower first) before delegating acquisition, regardless of caller argument order. Same-account calls (`a.equals(b)`) acquire a single lock once.
- Introduce the `JvmAccountLocker` adapter in `infrastructure.concurrency` implementing the port with a process-wide `ConcurrentHashMap<AccountNumber, ReentrantLock>`. The adapter:
  - Demands an active transaction via Spring's `TransactionSynchronizationManager.isSynchronizationActive()`; throws `IllegalStateException("paired locks require an active transaction")` otherwise — this is how requirement "no ad-hoc locking outside the surrounding transaction" is mechanically prevented.
  - Acquires both locks (or just one, for the same-account case) in canonical order using a bounded `tryLock(timeoutMs, MILLISECONDS)`. Failure becomes `LockAcquisitionTimeoutException` extending `DomainException` — surfaces through F03's handler later.
  - Registers a `TransactionSynchronization` `afterCompletion` callback that unlocks both held locks regardless of commit/rollback outcome. Locks released in **reverse** acquisition order to keep with lock-hygiene convention.
- Add `LockAcquisitionTimeoutException` to `com.bank.core.domain` extending the existing `DomainException` (the same parent F01 introduced and F02 reused). F03's handler will gain a `@ExceptionHandler` entry for it when F06 surfaces the lock path at the HTTP boundary — that handler edit is **not** part of F07 (no endpoint exists yet to trigger it).
- Add an ArchUnit rule asserting that **no class outside `com.bank.core.infrastructure.concurrency`** imports `java.util.concurrent.locks.ReentrantLock` or calls `TransactionSynchronizationManager` — this enforces "exactly one shared component used by every code path that mutates two accounts simultaneously" as a build-time check, not a code-review convention.
- Add an externalised lock wait timeout configuration property `bank.transfer.lock-wait-ms` (default `5000`) and bind it to `JvmAccountLocker` via constructor injection. The published manifest's `lock-wait-timeout` open decision closes in favour of "configurable, default 5 seconds, single-instance JVM lock; revisit if/when we go multi-instance and need DB-level row locks."
- Ship verification tests under `bootstrap/src/test/java/com/bank/core/concurrency/`:
  - **Domain unit test** for `LockAcquisitionTimeoutException` (carries the two account numbers and the wait bound).
  - **Spring integration test** `AccountLockerIntegrationTest` that wires the real `JvmAccountLocker` inside `@SpringBootTest` and asserts:
    1. **Order independence**: a test harness records the order in which a probe thread observes lock acquisition for `(A, B)` and `(B, A)` calls; both observe `min(A, B)` first.
    2. **Counter-direction stress**: 100 concurrent threads, half running a synthetic `transfer(A→B, +1)` and half `transfer(B→A, +1)`, all complete inside 10 s with the net counter on both "account" slots equal to their starting values. The synthetic transfer increments/decrements an `AtomicLong` keyed by account number while holding the paired locks, so the test exercises the lock contract without depending on F06's not-yet-existing balance/ledger machinery.
    3. **Cross-pair non-blocking**: while a long-running transfer holds locks on A and B, a parallel transfer on C and D completes promptly (under 200 ms), proving unrelated pairs do not serialise.
    4. **Released on commit and on rollback**: a transfer that commits releases its locks (next caller acquires immediately); a transfer that throws inside the callback rolls back the surrounding transaction and still releases its locks.
    5. **No deadlocks under contention**: the stress test asserts zero `LockAcquisitionTimeoutException` occurrences and that no thread takes longer than the configured wait.
  - **ArchUnit test** in `bootstrap`'s test suite that asserts the "no `ReentrantLock` outside `infrastructure.concurrency`" and "no `TransactionSynchronizationManager` outside `infrastructure.concurrency`" rules.

No public HTTP endpoint ships. No new database table. No edit to `application.yaml` other than the new `bank.transfer.lock-wait-ms` property in the default profile (and `application-test.yaml` lowering it to `500` to keep the test suite snappy).

## Capabilities

### New Capabilities
- `transfer-locking`: Single shared `AccountLocker` component that takes two account numbers, sorts them canonically (lower account number first by `String` comparison), acquires an exclusive lock on each in that order, executes the caller's transactional unit of work, and releases both locks at the surrounding transaction's commit or rollback. Failure to acquire a lock within a configured bound surfaces as `LockAcquisitionTimeoutException`. Build-time architecture rules forbid any other class from owning a lock primitive directly, so this component is the only place in the codebase where account-level write locks are taken.

### Modified Capabilities
None. F07 introduces a fresh concurrency primitive on top of F01 (`AccountNumber` value object) and consumes no existing capability's spec. F01's `Account` aggregate is unmodified; the locker takes `AccountNumber`s (the externally visible identity) rather than `Account` references so that lock acquisition does not require loading the aggregate from persistence — F06 will load the aggregates *inside* the paired-locks block, after the lock has been granted.

## Impact

- **Code**:
  - Adds `AccountLocker.java` (port interface) under `application/src/main/java/com/bank/core/application/concurrency/`.
  - Adds `JvmAccountLocker.java` (adapter) under `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/`.
  - Adds `LockAcquisitionTimeoutException.java` under `domain/src/main/java/com/bank/core/domain/`.
  - Adds the `AccountLockerIntegrationTest`, `LockAcquisitionTimeoutExceptionTest`, and the ArchUnit rule extension under `bootstrap/src/test/java/com/bank/core/concurrency/` and the existing `com/bank/core/architecture/ModuleBoundaryTest.java`.
- **Config**:
  - Adds `bank.transfer.lock-wait-ms: 5000` to `bootstrap/src/main/resources/application.yaml`.
  - Adds `bank.transfer.lock-wait-ms: 500` to `bootstrap/src/test/resources/application-test.yaml` so contention tests fail fast under deadlock rather than hanging the suite.
- **Schema**: None. F07 ships zero Flyway migrations. The lock is a JVM `ConcurrentHashMap` of `ReentrantLock`, transaction-scoped via `TransactionSynchronizationManager`. The published REQUIREMENTS doc and the legacy F07 draft both call out the single-instance assumption explicitly.
- **Build**: No new Gradle dependencies. `spring-tx` (transitive via `spring-boot-starter-data-jpa` from F00) supplies `TransactionSynchronizationManager`; `java.util.concurrent.locks` is JDK.
- **Conventions**:
  - Reaffirms F00's "application is Spring-free" rule — the `AccountLocker` *port* is plain Java; the *adapter* in `infrastructure` is where Spring lives. F02's `transactional-in-application` precedent applies here unchanged.
  - Reaffirms F00's "domain is JDK-only" rule — `LockAcquisitionTimeoutException` is a pure Java class extending the existing `DomainException`.
  - Adds two new ArchUnit rules (`ReentrantLock` and `TransactionSynchronizationManager` confinement) that codify "single source of truth for paired locks" as a compile-time guard rather than a code-review checkpoint.
- **Open decision closed**: `lock-wait-timeout` (manifest open question) → resolution: configurable via `bank.transfer.lock-wait-ms`, default `5000` ms in production, `500` ms in the test profile. The setting binds at adapter construction and is exposed via `JvmAccountLocker(properties).waitMs()` for diagnostic logging.
- **Open decision unchanged**: `idempotency` for `POST /transfers` (manifest) and `hot-account fairness` / `multi-instance` (legacy F07 draft) remain open and out of scope. F07 explicitly assumes one application instance; the JVM lock will not serialise transfers across multiple instances. A future change can swap the adapter for a DB row-lock implementation behind the same `AccountLocker` port without rewriting callers.
- **Downstream**:
  - **F06** (fund transfer) will inject `AccountLocker` and structure its use case as `locker.withPairedLocks(source, destination, () -> { … debit/credit/save journal … })`. F06 will also add the `@ExceptionHandler(LockAcquisitionTimeoutException.class)` entry to F03's `GlobalExceptionHandler`, mapping it to HTTP `503 SERVICE_UNAVAILABLE` (or a domain-specific error code to be decided in F06's proposal).
  - **F08** (account opening) seeds new accounts via a clearing-account transfer; that transfer uses F06's pipeline, so it inherits `AccountLocker` use transparently.
- **Backwards compat**: zero — no public API, no schema, no existing class is touched. Strictly additive.
- **Operational notes**: `LockAcquisitionTimeoutException` will be the signal that contention or a stuck transaction has exceeded the budget; F06's logging will include the two account numbers and the wait bound. The default 5 s budget is tuned for healthy traffic; under sustained hot-account contention, operators can raise it or accept the fail-fast.
