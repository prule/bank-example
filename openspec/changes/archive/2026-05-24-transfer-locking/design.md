## Context

F00 set up the four-module Gradle build with `bootstrap → infrastructure → application → domain` dependency arrows enforced by ArchUnit. F01 shipped the pure-domain `Account`/`Money`/`AccountNumber`/`AccountId` value objects and the four named mutators (`credit`, `debit`, `suspend`, `reactivate`). F02 shipped the immutable ledger and proved the pattern for the rest of the persistence story: `application` exposes plain-Java *ports*, `infrastructure` provides JPA/Spring *adapters*, `@Transactional` lives only on the adapter, and integration tests run inside `bootstrap`'s `@SpringBootTest` with the H2 PostgreSQL-compatibility test profile.

F07 sits in the manifest's `[F07]` build slot between `[F01, F02]` and `[F05, F06]`. The published spec at [openspec/specs/transfer-locking/spec.md](openspec/specs/transfer-locking/spec.md) is already authored in OpenSpec format; this change converts it into an `ADDED Requirements` delta plus the code that satisfies every scenario.

What the spec commits to:
- Canonical lock order by `AccountNumber` (lower first), independent of caller argument order.
- Locks span the surrounding transaction and release only on commit or rollback.
- A *single* shared component owns the rule — no controller, use case, or scheduler may invoke a lock primitive on its own.
- Counter-direction stress: 100 concurrent transfers in opposite directions between the same pair, no deadlock, both balances unchanged.
- Cross-pair correctness: A→B and C→A run together without corrupting A's final balance.

Constraints inherited from F00/F01/F02:
- `domain` is JDK-only — no Spring, no JPA, no Jackson. `LockAcquisitionTimeoutException` must therefore extend `DomainException` and live in `com.bank.core.domain`.
- `application` may not import Spring in production sources (F02 closed the `transactional-in-application` open decision in favour of this). The `AccountLocker` *port* is a plain Java interface.
- `infrastructure` may import Spring/JPA. The `JvmAccountLocker` *adapter* is a `@Component` here.
- F00's `ModuleBoundaryTest` runs in `bootstrap` and is the home for new ArchUnit rules.
- Tests that need Spring wiring live in `bootstrap/src/test/...` (F02 set the precedent — `infrastructure` has no `@SpringBootApplication`).

Open decisions touched: `lock-wait-timeout` from [openspec/config.yaml](openspec/config.yaml). This change closes it in favour of a configurable property (`bank.transfer.lock-wait-ms`, default 5000) and a fail-fast `LockAcquisitionTimeoutException` rather than open-ended waits.

## Goals / Non-Goals

**Goals:**
- One place — the `AccountLocker` port and its single adapter — owns the rule "two accounts in one transfer always lock in canonical order." Every other layer (use cases, controllers, schedulers) calls this port; no other code in the repository imports `ReentrantLock` or `TransactionSynchronizationManager` (enforced by ArchUnit).
- The locker accepts `AccountNumber` arguments and does not require an `Account` aggregate to exist in memory or in the database. F06 will load the aggregates *inside* the paired-locks block, after lock acquisition has succeeded — this avoids "lock A → load A → load B → lock B → deadlock with the other direction" ordering bugs.
- The locker enforces an active surrounding transaction: calls without one throw `IllegalStateException`. This is how the spec's "single source of truth" requirement becomes a *runtime* guard, complementing the ArchUnit *build-time* guard.
- Lock release is bound to transaction completion via Spring's `TransactionSynchronization.afterCompletion(...)`, so a commit or a rollback (including an uncaught exception in the callback or a JVM crash that triggers transaction rollback) frees the lock for the next waiter.
- The counter-direction stress test runs 100 threads in 10 s using a synthetic in-memory "balance" map, proving the lock contract without depending on F06's not-yet-existing balance/ledger machinery. The test fails fast on `LockAcquisitionTimeoutException` rather than hanging the suite.
- The wait timeout is configurable and visible: `bank.transfer.lock-wait-ms`, defaulted at 5000 ms in production, 500 ms in the test profile.

**Non-Goals:**
- **Multi-instance correctness.** A JVM `ReentrantLock` map does not serialise across separate JVMs. The legacy F07 draft already calls this out: single-instance assumption. A future change can swap the adapter to `SELECT account FOR UPDATE` behind the same port without rewriting any caller.
- **Distributed lock manager** (Redis, ZooKeeper, etc.). Outside scope; see Non-Goals above.
- **Lock fairness / queue ordering.** The adapter uses Java's default `ReentrantLock(false)` (non-fair). Under sustained hot-account contention there is no guarantee that waiters acquire in arrival order; throughput is preferred over starvation-freedom. The `hot-account fairness` legacy open question stays open and is explicitly out of scope.
- **Optimistic version checks** on `Account`. The spec mentions optimistic version as "defence in depth"; F07 ships only the pessimistic mechanism. If F06 wants `@Version` on the JPA Account entity, that can be added there.
- **Account JPA entity, migration, or repository.** F07 does not introduce the `account` table. F05/F06 own that. F07 talks in `AccountNumber` strings only; the lock map is `ConcurrentHashMap<AccountNumber, ReentrantLock>`.
- **HTTP surface, controller, or `@ExceptionHandler` entry for `LockAcquisitionTimeoutException`.** No endpoint exists in F07 to trigger this; F06 will wire the handler when it lands.
- **Lock-acquisition logging at INFO level** under normal traffic. The adapter logs at DEBUG on each acquisition/release and at WARN only on timeout. Per-transfer INFO logs would be too chatty; F06 owns transfer-level observability.

## Decisions

### Locking primitive: JVM `ReentrantLock`, not DB row locks

Pessimistic locking on a relational row (`SELECT … FOR UPDATE`) is the textbook answer for "make this transfer atomic against concurrent transfers." It is also the right *eventual* answer for multi-instance deployments. F07 chooses the JVM `ReentrantLock` map instead because:

1. **No account table exists yet.** F02 added `journal_entry` and `ledger_movement`; the `account` table belongs to F06's persistence work. Forcing F07 to introduce the account schema bloats its scope from "concurrency primitive" to "concurrency + persistence."
2. **The single-instance assumption is documented and explicit.** Both the published REQUIREMENTS doc and the legacy F07 draft acknowledge that this implementation targets one application instance. A JVM lock satisfies the spec under this assumption.
3. **The port abstracts the choice.** `AccountLocker.withPairedLocks(a, b, runnable)` is satisfied by either a JVM lock or a DB row lock; if a future multi-instance change demands DB-level enforcement, the adapter is replaced and callers are untouched.
4. **Faster tests.** `ReentrantLock.tryLock(500ms)` returns quickly; a DB-lock implementation would need a real database and would slow the stress suite.

Rejected alternative — *DB advisory locks* (e.g. `pg_advisory_xact_lock`). H2 does not support PostgreSQL's advisory-lock function family; the test profile (H2 in PostgreSQL-compat mode) would need a stub. Adds H2-vs-prod-database divergence we have deliberately kept out of the build so far.

Rejected alternative — *a `transfer_lock` table with `SELECT FOR UPDATE` on a row keyed by `account_number`*. This works on H2 and PostgreSQL but introduces a synthetic table that serves only as a synchronisation primitive, and requires `INSERT IGNORE` semantics that vary across databases. Not worth the schema baggage when the JVM map is correct under the single-instance assumption.

### Canonical order: lexicographic on `AccountNumber` value

`AccountNumber` wraps a non-blank `String` (F01). The canonical order is `min(a, b)` by `String.compareTo` — the natural order on the wrapped value. The locker normalises input by:

```java
AccountNumber first  = a.value().compareTo(b.value()) <= 0 ? a : b;
AccountNumber second = (first == a) ? b : a;
```

Same-account calls (`a.equals(b)`) take the lock once and return after the callback. This matches the spec's "lower account number first" wording without inventing a new comparator.

Rejected alternative — *canonical order by `AccountId` UUID*. The spec is explicit that it is the account *number* (the externally visible identifier, a `String`) that determines order, not the internal UUID. Following the spec text avoids surprising operators who can name an account but not its UUID.

### Active-transaction enforcement: `TransactionSynchronizationManager`

The adapter checks `TransactionSynchronizationManager.isSynchronizationActive()` before acquiring any lock. If false, it throws `IllegalStateException("paired locks require an active transaction")` — fail-fast with a clear message. This catches accidental misuse (e.g. a controller that calls the locker without a `@Transactional` boundary), and it makes the "released on commit/rollback" requirement *enforceable* — without an active transaction, there is no completion event to register a release on.

Rejected alternative — *wrap the callback in the locker's own internally-managed transaction*. That would let any caller wrap arbitrary code in a transactional block, blurring transactional boundaries. F02 set the rule: transactions are configured on the adapter that owns the work (the JPA persistence adapter), not the locker. F06's use case will be `@Transactional`; the locker just verifies the caller has obeyed that.

### Lock-release wiring: `afterCompletion`, not `afterCommit`

`TransactionSynchronization` offers `afterCommit` (commit-only) and `afterCompletion` (commit *or* rollback). The locker uses `afterCompletion` because the spec demands release on rollback too. `STATUS_COMMITTED`, `STATUS_ROLLED_BACK`, and `STATUS_UNKNOWN` (transaction system failure) all release the locks.

Locks are released in **reverse acquisition order** (second-acquired first), matching standard lock-hygiene convention and making it easier to reason about timing in stack traces if anything ever throws during release.

Rejected alternative — *try/finally release in a wrapper method*. Spring's transaction infrastructure already runs the synchronisation callbacks in the right order relative to JPA flushing and connection release. Mixing manual try/finally with transaction synchronisation creates double-release bugs. The `afterCompletion` hook is the idiomatic choice.

### Wait-timeout handling: `tryLock(ms)` + dedicated exception

`tryLock(timeoutMs, MILLISECONDS)` returning `false` triggers `LockAcquisitionTimeoutException`. The adapter releases any already-held lock from the same call before throwing — so a transfer that grabbed `min(A, B)` but timed out waiting for `max(A, B)` does not hold the first lock past its own failure.

`LockAcquisitionTimeoutException` extends `DomainException` (F01) and carries `(AccountNumber first, AccountNumber second, long waitMs)`. F06 will add the `@ExceptionHandler` mapping; F07 just throws the type. The interrupted case (`InterruptedException`) re-asserts the thread's interrupt flag and rethrows as `LockAcquisitionTimeoutException` with `waitMs = 0` and a message identifying the interrupt — interruptions during DB transactions are operationally indistinguishable from timeouts from the caller's perspective.

### ArchUnit confinement: build-time enforcement of "single source of truth"

The spec's "exactly one component is responsible for acquiring the paired locks" requirement becomes two ArchUnit rules added to `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java`:

```text
no class outside `com.bank.core.infrastructure.concurrency..` shall depend on `java.util.concurrent.locks.ReentrantLock`
no class outside `com.bank.core.infrastructure.concurrency..` shall depend on `org.springframework.transaction.support.TransactionSynchronizationManager`
```

Test code is exempt by package (`com.bank.core.concurrency` test sources reference the lock indirectly via the port; if a test needs `ReentrantLock` directly, it lives under `..infrastructure.concurrency` in `bootstrap/src/test` and the rule's scope-by-source-path keeps it green).

This converts the spec's code-review requirement into an automated guard. A future change that introduces a second lock owner cannot reach merge without either removing the rule (visible diff) or restructuring under `infrastructure.concurrency`.

Rejected alternative — *code review only*. The spec is explicit that exactly one component is allowed; relying on a human to spot a stray `new ReentrantLock()` in a six-month-old PR is not a guarantee.

### Port placement: `application.concurrency`

The `AccountLocker` interface lives in `com.bank.core.application.concurrency`. F02 created `com.bank.core.application.ledger` for the journal port; this change parallels that with a `concurrency` sub-package. Other application sub-packages (`use-case`, `command`, `result`) are reserved by F00 conventions; `concurrency` is a new peer because the lock is a cross-cutting application concern.

Adapter lives in `com.bank.core.infrastructure.concurrency.JvmAccountLocker`. The class name (`JvmAccountLocker`) telegraphs the implementation strategy so that a future `DbRowAccountLocker` can sit alongside it without naming collisions.

### Configuration property binding

`@ConfigurationProperties(prefix = "bank.transfer")` on a small `TransferLockingProperties` record with one field, `long lockWaitMs`. The record is declared in `infrastructure.concurrency` (where Spring lives). The adapter takes the record via constructor injection. `application.yaml` declares the default:

```yaml
bank:
  transfer:
    lock-wait-ms: 5000
```

`application-test.yaml` overrides to `500` for fail-fast stress tests.

Rejected alternative — *a hardcoded constant*. The manifest's `lock-wait-timeout` open decision explicitly demanded the bound be configurable before production. Closing it via a hardcoded value would leave the decision still open.

## Risks / Trade-offs

- **Single-instance assumption is load-bearing.** A second JVM running against the same database would defeat the lock and the deadlock-free property would hold only within each JVM, not globally. → Mitigation: documented in this design, in the proposal, in the spec (`unrelated accounts proceed without waiting` is single-instance-bounded), and called out as the path to the `multi-instance` open question. The port abstraction means swapping to a DB-row implementation later is a single-adapter swap.
- **Non-fair `ReentrantLock` may starve waiters under sustained hot-account contention.** → Mitigation: `lock-wait-ms` is configurable so operators can raise the budget; the `hot-account fairness` legacy open question is acknowledged and deferred. The stress test (50/50 counter-direction) exercises balanced contention, which is the spec's literal scenario; pathological one-sided load is out of scope.
- **`tryLock` interruption is reported as a timeout.** A thread interrupted mid-acquisition cannot distinguish "operator cancelled my request" from "the database server died": both surface as `LockAcquisitionTimeoutException`. → Mitigation: the exception message identifies the InterruptedException as the cause when relevant; the thread's interrupt flag is re-asserted so callers can detect interruption if they care. F06 can map interrupt-driven timeouts differently at the HTTP layer if it becomes a real operational concern.
- **`TransactionSynchronizationManager.registerSynchronization(...)` requires the synchronisation list to be active *before* lock acquisition.** If a future caller subverts this by acquiring locks first and starting the transaction afterward, the release callback never registers and locks leak until the JVM exits. → Mitigation: the adapter's `isSynchronizationActive()` check rejects that ordering with `IllegalStateException` at the *first* lock attempt, before any state is touched. The ArchUnit rule prevents callers from reaching `TransactionSynchronizationManager` directly to bypass the check.
- **Lock release runs after the transaction commits / rolls back, not synchronously with the use-case method's return.** A subsequent test or caller that immediately re-acquires the same locks observes them as released only *after* the commit completes (i.e. after the `@Transactional` proxy returns control). → Mitigation: this is the correct semantics (locks must outlive the JPA flush and connection release within the same transaction); the stress test's "released on commit" scenario asserts the post-commit observability and is the operationally meaningful contract.
- **A `ConcurrentHashMap` of `ReentrantLock`s never shrinks** — once an `AccountNumber` has been locked once, its `ReentrantLock` instance lives in the map for the JVM lifetime. → Mitigation: `AccountNumber`s are bounded (one per account, plus a clearing account), so the map size is `O(number of accounts ever transacted)`. At ~64 bytes per entry, a million accounts costs ~64 MB — negligible on a service designed to serve a small bank. A future eviction policy can be added if needed.

## Migration Plan

Pure addition. No data migration, no rolling-deploy concern, no feature flag.

1. **Ship the port + exception + adapter together.** The adapter is `@Component`; without it, Spring fails to start when the port has no implementation. Splitting across changes would create a temporary broken state.
2. **Ship the ArchUnit rule alongside the implementation.** Adding the rule first would fail the build (the locker class doesn't exist yet); adding it after creates a window where the rule isn't enforced. Same-change shipping is correct.
3. **Default config is safe.** `bank.transfer.lock-wait-ms: 5000` in `application.yaml` means existing deployments boot with a 5 s budget. No environment-variable override is needed for ship.
4. **Rollback** is `git revert` of the change: removing `JvmAccountLocker` removes the Spring bean, `AccountLocker` becomes an unimplemented port. Since no caller in the codebase imports `AccountLocker` yet (F06 ships next), rollback is mechanically safe — nothing is broken because nothing depends on it.
5. **Forward link**: F06's proposal must inject `AccountLocker` and add the `@ExceptionHandler(LockAcquisitionTimeoutException.class)` entry to F03's `GlobalExceptionHandler`. F07's design closes the `lock-wait-timeout` open question; F06 will not need to re-litigate it.

## Open Questions

None blocking F07. Two known carry-forward items:

- **Multi-instance enforcement** (legacy F07 question). Out of scope; addressed if/when the deployment topology changes. The port abstraction means the answer is "swap the adapter."
- **Hot-account fairness under sustained contention** (legacy F07 question). Non-fair `ReentrantLock` is fine for balanced contention; under one-sided load, operators raise `lock-wait-ms` or accept fail-fast. Revisit if a real workload demands fair queuing.
