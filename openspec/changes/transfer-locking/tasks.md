## 1. Domain exception

- [ ] 1.1 Create `com.bank.core.domain.LockAcquisitionTimeoutException` extending `DomainException` with `(AccountNumber first, AccountNumber second, long waitMs)` constructor and `firstAccount()` / `secondAccount()` / `waitMs()` accessors; message identifies both accounts and the elapsed wait.
- [ ] 1.2 `LockAcquisitionTimeoutExceptionTest` (JUnit 5) under `domain/src/test/...` covers accessor round-trip and message formatting.
- [ ] 1.3 Confirm `grep -R 'org.springframework\|jakarta.persistence\|com.fasterxml.jackson' domain/src/main/` still returns zero matches.

## 2. Application port

- [ ] 2.1 Create `com.bank.core.application.concurrency.AccountLocker` interface with a single method `void withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction)`; Javadoc names downstream consumers (F06 fund-transfer, F08 account-opening via F06).
- [ ] 2.2 Confirm `grep -R 'org.springframework\|jakarta.persistence' application/src/main/` still returns zero matches (F02's `applicationHasNoFrameworkDependencies` ArchUnit rule stays green).

## 3. Infrastructure adapter

- [ ] 3.1 Create `com.bank.core.infrastructure.concurrency.TransferLockingProperties` annotated `@ConfigurationProperties(prefix = "bank.transfer")` with a single `long lockWaitMs` field; register via `@EnableConfigurationProperties` on the existing bootstrap configuration (or `@ConfigurationPropertiesScan` if already enabled).
- [ ] 3.2 Create `com.bank.core.infrastructure.concurrency.JvmAccountLocker` (`@Component`) implementing `AccountLocker`. Fields: `private final ConcurrentHashMap<AccountNumber, ReentrantLock> locks = new ConcurrentHashMap<>(); private final long waitMs;`. Constructor takes `TransferLockingProperties`.
- [ ] 3.3 Implement `withPairedLocks(a, b, runnable)`:
  - Validate non-null arguments; throw `NullPointerException` (standard) for null.
  - Check `TransactionSynchronizationManager.isSynchronizationActive()`; throw `IllegalStateException("paired locks require an active transaction")` otherwise.
  - Compute canonical order: `first = a.value().compareTo(b.value()) <= 0 ? a : b; second = (first == a) ? b : a`.
  - Same-account shortcut: when `a.equals(b)`, acquire a single lock and register a single-release callback.
  - Acquire `firstLock` via `tryLock(waitMs, MILLISECONDS)`; on `false`, throw `LockAcquisitionTimeoutException(first, second, waitMs)`.
  - Acquire `secondLock` likewise; on `false`, **release `firstLock`** and throw `LockAcquisitionTimeoutException`.
  - Register `TransactionSynchronization.afterCompletion(status)` that unlocks `secondLock` then `firstLock`.
  - Execute `runnable.run()`.
  - Handle `InterruptedException`: re-assert `Thread.currentThread().interrupt()`, release any held lock, throw `LockAcquisitionTimeoutException(first, second, 0)` with the interrupt as `cause`.
- [ ] 3.4 Expose `long waitMs()` accessor on `JvmAccountLocker` for diagnostic use (referenced in the spec scenario "Default production wait bound is 5 seconds").
- [ ] 3.5 Log at DEBUG: `acquired paired locks on {first}, {second} (waitMs={waitMs})` after acquisition; log at WARN on timeout including the elapsed time.

## 4. Configuration

- [ ] 4.1 Add `bank.transfer.lock-wait-ms: 5000` to `bootstrap/src/main/resources/application.yaml` under a new top-level `bank:` key (or extend if one already exists).
- [ ] 4.2 Add `bank.transfer.lock-wait-ms: 500` to `bootstrap/src/test/resources/application-test.yaml`.

## 5. ArchUnit confinement

- [ ] 5.1 Extend `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java` with `noClassesOutsideConcurrencyAdapterMayUseReentrantLock` asserting no class outside `com.bank.core.infrastructure.concurrency..` depends on `java.util.concurrent.locks.ReentrantLock` in production sources.
- [ ] 5.2 Add `noClassesOutsideConcurrencyAdapterMayUseTransactionSynchronizationManager` asserting no class outside `com.bank.core.infrastructure.concurrency..` depends on `org.springframework.transaction.support.TransactionSynchronizationManager` in production sources.
- [ ] 5.3 Verify both new rules pass on the existing codebase (sanity check before introducing the adapter), then verify they still pass after `JvmAccountLocker` lands.

## 6. Integration tests

- [ ] 6.1 Create `bootstrap/src/test/java/com/bank/core/concurrency/AccountLockerIntegrationTest` annotated `@SpringBootTest(properties = "bank.transfer.lock-wait-ms=500")` (or rely on test-profile YAML). Inject `AccountLocker` and a `TransactionTemplate`/`PlatformTransactionManager` for wrapping the runnables in real transactions.
- [ ] 6.2 **Order-independence test**: spawn two threads calling `withPairedLocks(A, B, ...)` and `withPairedLocks(B, A, ...)` concurrently; assert via a test probe (record the `ReentrantLock` identity captured at acquisition time) that both threads acquire `min(A.value(), B.value())` first.
- [ ] 6.3 **Counter-direction stress test**: 100 threads, half running synthetic `transfer(A→B)` and half `transfer(B→A)` against a `Map<AccountNumber, AtomicLong>`. Each transfer wraps `transactionTemplate.execute(status -> { locker.withPairedLocks(src, dst, () -> { balances.get(src).addAndGet(-1); balances.get(dst).addAndGet(+1); }); return null; })`. Assert: all complete within 10 s, zero `LockAcquisitionTimeoutException`, both balances equal to their starting values.
- [ ] 6.4 **Cross-pair test**: T1 holds locks on `(A, B)` inside a transaction that sleeps; T2 calls `withPairedLocks(C, D, ...)`; assert T2 completes in under 200 ms while T1 is still holding its locks.
- [ ] 6.5 **Released on commit**: a transaction acquires `(A, B)`, commits; immediately afterward a second call acquires the same pair without waiting (under 50 ms).
- [ ] 6.6 **Released on rollback**: a transaction acquires `(A, B)`, the runnable throws; assert rollback released both locks (next caller acquires immediately).
- [ ] 6.7 **Timeout becomes `LockAcquisitionTimeoutException`**: T1 holds `A` inside a long-running transaction; T2's call to `withPairedLocks(A, B, ...)` throws `LockAcquisitionTimeoutException` after 500 ms; assert thrown instance's `firstAccount()`/`secondAccount()` are in canonical order and `waitMs() == 500`.
- [ ] 6.8 **Outside-transaction call rejected**: call `locker.withPairedLocks(A, B, ...)` directly (no `@Transactional`, no `TransactionTemplate` wrap); assert `IllegalStateException` with the expected message; assert the runnable did not execute.
- [ ] 6.9 **Same-account call**: `withPairedLocks(A, A, runnable)`; assert the runnable executes and acquires the lock once (hold count 1 inside the callback, observed via test-only accessor or by re-entering and observing 2).
- [ ] 6.10 Tests use bounded thread pools (`Executors.newFixedThreadPool(100)`) with `awaitTermination(15, SECONDS)` so a hang fails the suite rather than blocking it indefinitely.

## 7. Forward-compat hygiene

- [ ] 7.1 `AccountLocker` Javadoc lists downstream consumers (F06 fund-transfer; F08 account-opening via F06) and the F06 todo: `@ExceptionHandler(LockAcquisitionTimeoutException.class)` in `GlobalExceptionHandler`.
- [ ] 7.2 `JvmAccountLocker` class-level Javadoc explains the single-instance assumption and points at the `multi-instance` legacy open question.
- [ ] 7.3 `openspec/config.yaml`: close the `lock-wait-timeout` open decision (resolution: configurable via `bank.transfer.lock-wait-ms`, default 5000, test profile 500).

## 8. Verification

- [ ] 8.1 `./gradlew :domain:test` passes (F01 + F02 + new exception test).
- [ ] 8.2 `./gradlew :application:test` — no source tests; NO-SOURCE pass.
- [ ] 8.3 `./gradlew :infrastructure:test` — no source tests (adapter tested through `bootstrap`); NO-SOURCE pass.
- [ ] 8.4 `./gradlew :bootstrap:test` passes — all prior tests plus the seven new locker scenarios and the two new ArchUnit rules.
- [ ] 8.5 `./gradlew clean build` — full project green; ArchUnit confines `ReentrantLock` and `TransactionSynchronizationManager` to `infrastructure.concurrency`.
- [ ] 8.6 `./gradlew :bootstrap:bootRun` + `curl /actuator/health` — 200 UP confirming no boot regression from the new bean/config.
- [ ] 8.7 `grep -R 'ReentrantLock\|TransactionSynchronizationManager' --include='*.java' domain/src/main application/src/main infrastructure/src/main bootstrap/src/main` returns matches only under `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/`.
