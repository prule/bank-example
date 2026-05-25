## Why

F07 shipped `JvmAccountLocker` as the single implementation of the `AccountLocker` port: a process-wide `ConcurrentHashMap<AccountNumber, ReentrantLock>` plus a `TransactionSynchronization.afterCompletion` release hook. It's correct, fast, and zero-infrastructure — but it's also fundamentally single-instance. A second JVM running the same service against the same database would defeat the lock entirely: the canonical-order rule holds within each JVM but not across them, so two opposite-direction transfers between the same accounts on different JVMs would deadlock at the row-level DB lock or — worse — both proceed and corrupt the cached balance. F07's own Javadoc names this constraint explicitly and `openspec/config.yaml` carries the `multi-instance` open decision tracking the path to a DB-row-lock adapter when deployment topology demands it.

Today that "when" has arrived in spec form even though we still ship single-instance: with F11 audit logs being grep'd for `drifted=K` in operator dashboards, the value of running multiple bank-core instances (rolling deploys, blue/green, load testing) is concrete. The `AccountLocker` port has always abstracted the choice — the swap is local to `com.bank.core.infrastructure.concurrency`. This change introduces the DB-backed implementation and the runtime switch.

The DB-backed adapter uses `SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE` to acquire row-level exclusive locks on both accounts in canonical order in a single round-trip. The DB releases the locks at the surrounding transaction's COMMIT or ROLLBACK — no application-side release hook needed, and the lock is naturally cross-instance because every JVM in the cluster contends for the same row in the same database. The wait bound (`bank.transfer.lock-wait-ms`, default `5000`) is honoured by setting `LOCK_TIMEOUT` (H2) / `lock_timeout` (PostgreSQL) per call; on timeout, the JDBC driver throws a known SQLSTATE which the adapter catches and converts to `LockAcquisitionTimeoutException` carrying the same diagnostic fields the JVM locker uses today.

The strategy switch is one new `String strategy` field on `TransferLockingProperties` (`bank.transfer.locker`, default `jvm`). Both implementations are `@Component`-scanned, but each is gated by `@ConditionalOnProperty` so exactly one is constructed per Spring context — the runtime contract is enforced by Spring's conditional bean wiring, not by manual `if`s in the use case.

## What Changes

- Introduce `DbAccountLocker` in `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/`. `@Component @ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "db")`. Constructor injects `TransferLockingProperties` and a `JdbcTemplate` (Spring Boot auto-configures it from the existing `DataSource`). Implements `AccountLocker.withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction)`:
  1. Null-check all three arguments.
  2. Verify a Spring transaction is active via `TransactionSynchronizationManager.isSynchronizationActive()`; throw `IllegalStateException` with the same message shape as `JvmAccountLocker` if not — the spec scenario "Call outside a transaction is rejected" applies identically to both implementations.
  3. Compute the canonical ordering of `(a, b)`: lower `String.compareTo` first, identical inputs collapsed to one. Build a `List<String> accountNumbers` containing one entry (same-account case) or two entries in canonical order.
  4. Set the database's lock-wait timeout for the current transaction: `jdbcTemplate.execute("SET LOCK_TIMEOUT " + waitMs)` (H2 dialect; see Decisions in design.md for the PostgreSQL substitution `SET LOCAL lock_timeout = '<ms>ms'`).
  5. Run a single parameterised `SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE` (or `IN (?)` for the same-account case). H2 and PostgreSQL both honour `ORDER BY` before `FOR UPDATE` so the rows are locked in canonical order within the one statement. The statement is wrapped in a try/catch on `DataAccessException`: if the unwrapped `SQLException`'s SQLSTATE is `HYT00` / `40001` (H2 LOCK_TIMEOUT — confirm exact codes during apply via H2 `org.h2.api.ErrorCode.LOCK_TIMEOUT_1 = 50200`) or `55P03` (PostgreSQL lock_not_available), throw `LockAcquisitionTimeoutException(first, second, waitMs)`. Any other `DataAccessException` propagates unchanged.
  6. The DB holds the row locks until commit/rollback — no `TransactionSynchronization` registration needed, no application-side release callback. This is one of the two structural differences from `JvmAccountLocker`.
  7. Reset the lock timeout via `jdbcTemplate.execute("SET LOCK_TIMEOUT 0")` after the locks are acquired and before running `inTransaction` (defensive — H2's `SET LOCK_TIMEOUT` is session-scoped, so the next user of the pooled connection inherits whatever value we left behind; resetting after the FOR UPDATE makes the bound apply only to the lock acquisition, not to subsequent statements inside `inTransaction.run()`).
  8. Invoke `inTransaction.run()`. DB releases locks when the surrounding transaction commits or rolls back.
  9. Class Javadoc documents the multi-instance correctness story, the SQLSTATE mapping, and why the explicit `SET LOCK_TIMEOUT 0` reset matters for connection-pooled environments.
- Modify `JvmAccountLocker.java`: add `@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "jvm", matchIfMissing = true)`. The `matchIfMissing = true` keeps the JVM locker as the default — applications that don't opt into the DB locker behave exactly as before. The bean's body, semantics, and tests are unchanged.
- Modify `TransferLockingProperties.java`. Today it is `record TransferLockingProperties(long lockWaitMs)`. Extend to `record TransferLockingProperties(long lockWaitMs, String strategy)` with a compact constructor that:
  - Defaults `strategy` to `"jvm"` when null or blank (`strategy = (strategy == null || strategy.isBlank()) ? "jvm" : strategy.toLowerCase()`).
  - Validates `strategy` is one of `{"jvm", "db"}`; otherwise throw `IllegalArgumentException("bank.transfer.locker must be 'jvm' or 'db' (was: '" + strategy + "')")`. This fails the application context build with a clear message if someone fat-fingers the property.
- Modify `bootstrap/src/main/resources/application.yaml`. Under the existing `bank.transfer` block, add `locker: jvm` with an inline comment explaining the two options (`jvm` for single-instance, `db` for multi-instance via `SELECT FOR UPDATE`) and naming the wait-timeout property that governs both implementations. Production defaults to `jvm` — the multi-instance topology is opt-in.
- Modify `bootstrap/src/test/resources/application-test.yaml`. NO change to the default — tests continue to exercise the JVM path by default. Tests that want the DB path opt in via `@TestPropertySource(properties = "bank.transfer.locker=db")` on the specific class.
- Update the ArchUnit boundary rules in `bootstrap/src/test/java/com/bank/core/architecture/ModuleBoundaryTest.java`:
  - The existing `noClassesOutsideConcurrencyAdapterMayUseReentrantLock` rule stays — `DbAccountLocker` does not use `ReentrantLock`.
  - The existing `noClassesOutsideConcurrencyAdapterMayUseTransactionSynchronizationManager` rule stays — `DbAccountLocker` still uses `TransactionSynchronizationManager.isSynchronizationActive()` for the active-transaction check (same as `JvmAccountLocker`), so the rule remains relevant.
  - No new rule needed — the existing confinement already covers both implementations.
- Tests:
  - **Unit/integration test** `bootstrap/src/test/java/com/bank/core/concurrency/DbAccountLockerIntegrationTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@TestPropertySource(properties = "bank.transfer.locker=db")`):
    - `correctImplementationIsWired` — asserts `context.getBean(AccountLocker.class) instanceof DbAccountLocker` and `context.getBeansOfType(JvmAccountLocker.class).isEmpty()`. Strategy-switch wiring verification.
    - `pairedLocksHeldUntilCommit` — seed two accounts, run a `@Transactional` block that calls `withPairedLocks(A, B, runnable)` and verifies that a probe SELECT against the same rows from a separate connection times out within the configured wait budget. Mirrors `AccountLockerIntegrationTest.locksReleasedOnCommit` from the JVM suite but probes via a separate connection.
    - `oppositeDirectionConcurrentCallsDoNotDeadlock` — 50 concurrent calls, half `(A, B)`, half `(B, A)`, each performing a synthetic increment on a balance map. Same scenario as the JVM suite's "Counter-direction concurrent transfers never deadlock". With `bank.transfer.lock-wait-ms=500` (the test profile), all 50 complete within a few seconds, zero `LockAcquisitionTimeoutException`. The synthetic balances match expected.
    - `crossPairContentionSerialises` — calls `(A, B)` and `(C, A)` overlap. Both complete, no deadlock, A's synthetic balance is the deterministic sum of both adjustments.
    - `timeoutMapsToLockAcquisitionTimeoutException` — thread T1 holds the lock on A inside an in-flight transaction; T2 calls `withPairedLocks(A, B, ...)` with a tight `lock-wait-ms`; T2 throws `LockAcquisitionTimeoutException` carrying the two account numbers and the wait bound. Verifies the SQLSTATE-to-domain-exception mapping.
    - `outOfTransactionCallRejected` — calls `withPairedLocks` without a `@Transactional` boundary; throws `IllegalStateException` with the same message as the JVM implementation.
    - `sameAccountCallLocksOnce` — `withPairedLocks(A, A, runnable)` runs the runnable exactly once and the `SELECT ... FOR UPDATE` statement loads exactly one row (verifiable by enabling SQL TRACE and counting `SELECT id FROM account WHERE account_number IN` occurrences, or by inspecting the rendered SQL via `JdbcTemplate.queryForList`).
    - `missingAccountIsTransparent` — `withPairedLocks(EXISTING, MISSING-NEVER-CREATED, runnable)`: the runnable still runs (the missing row simply contributes no lock), no exception is thrown by the locker itself. This pins F06's separation of concerns: the locker is responsible for serialising, the use case is responsible for existence checks.
  - **Existing test rename / parametrisation**: rename `AccountLockerIntegrationTest` to `JvmAccountLockerIntegrationTest` and add `@TestPropertySource(properties = "bank.transfer.locker=jvm")` so it's symmetric with the new test and explicit about which strategy it covers. The existing assertions stay identical. (Alternative: refactor into a parameterised abstract base — rejected as overkill for two implementations.)
  - **`TransferLockingPropertiesTest`** — new unit test in `infrastructure/src/test/java/com/bank/core/infrastructure/concurrency/`:
    - Default strategy is `"jvm"` when null or blank.
    - `"JVM"` and `"DB"` (any case) are normalised to lowercase.
    - `"sql"` / `"redis"` / any other value throws `IllegalArgumentException` with the documented message.
    - `lockWaitMs` is preserved unchanged.
  - **`TransferAtomicityIntegrationTest`** (existing, in `bootstrap/src/test/java/com/bank/core/persistence/transfer/`) — verify it still passes with the JVM strategy (default). The same atomicity scenarios SHOULD also pass with the DB strategy; add `@TestPropertySource(properties = "bank.transfer.locker=db")` to a duplicated `TransferAtomicityIntegrationDbLockerTest` class to verify F06 works end-to-end against the DB locker too. (Same test cases, one annotation difference.)
  - **ArchUnit check**: F00's `ModuleBoundaryTest` rules continue to pass — neither `ReentrantLock` nor `TransactionSynchronizationManager` is imported outside `com.bank.core.infrastructure.concurrency..`. `JdbcTemplate` is used by `DbAccountLocker` but is already used by other infrastructure adapters, so no boundary violation.

## Capabilities

### New Capabilities

None. The `transfer-locking` capability spec already exists and covers both implementations conceptually (the port already abstracts the choice).

### Modified Capabilities

- `transfer-locking`: refine the "Single lock-acquisition component" requirement to allow either `JvmAccountLocker` or `DbAccountLocker` (still exactly one constructed per context, gated by `bank.transfer.locker`). Add a new requirement "Configurable locker strategy" defining the property, defaults, validation, and the mutually-exclusive bean contract. Add a new scenario to the "Locks span the surrounding transaction" requirement asserting that the DB locker releases via the database's transaction commit (not the application's `TransactionSynchronization.afterCompletion` hook) — same observable behaviour, different mechanism. The existing canonical-order, deadlock-freedom, cross-pair, and timeout scenarios become implementation-agnostic and apply to both adapters.

## Impact

- **Code**:
  - `application/src/main/java/com/bank/core/application/concurrency/AccountLocker.java` — Javadoc update only (name both implementations in the "Implementations" section). No signature change.
  - `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/JvmAccountLocker.java` — add `@ConditionalOnProperty(matchIfMissing = true, havingValue = "jvm", name = "bank.transfer.locker")`.
  - `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/DbAccountLocker.java` (new).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/TransferLockingProperties.java` — extend the record with the `strategy` field, validation.
  - `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` — no change. `@EnableConfigurationProperties` already registers `TransferLockingProperties`; the conditional beans are picked up by component scan.
- **Configuration**:
  - `bootstrap/src/main/resources/application.yaml` — add `bank.transfer.locker: jvm` with documentation.
  - `bootstrap/src/test/resources/application-test.yaml` — no change.
- **Schema / migrations**: none. The DB locker uses the existing `account` table and its primary-key index.
- **OpenAPI**: none. The locking strategy has no HTTP surface.
- **Build**: no new Gradle dependency. `JdbcTemplate` and `DataSource` are already on the classpath via `spring-boot-starter-data-jpa`.
- **Conventions**:
  - Reaffirms F00's "configuration knobs live in `application*.yaml`" — the new switch is externalised.
  - Reaffirms F07's "single source of truth for paired locks" — exactly one implementation is constructed at runtime, the `@ConditionalOnProperty` annotations make this verifiable from the spec scenarios.
  - The ArchUnit confinement rules for `ReentrantLock` and `TransactionSynchronizationManager` continue to enforce that the only legitimate paired-lock acquirers live in `com.bank.core.infrastructure.concurrency`.
- **Open decisions**:
  - **Closed by this change**: `multi-instance` (`openspec/config.yaml`). With `bank.transfer.locker=db`, the locker is correct under multi-instance deployment. Multi-instance test fixtures are out of scope here (would require a second JVM and shared H2 server, or a TestContainers Postgres) — but the architectural blocker is removed.
  - **No new open decision opened.**
- **Downstream**:
  - **F06** (`fund-transfer`) — already injects `AccountLocker` via the port. No code change required. Tests that previously assumed `JvmAccountLocker` instance type (via reflection or cast) would need adjustment — none exist today.
  - **F08** (`account-opening`) — uses F06 transitively. No change.
  - **F11** (`balance-drift-detection`) — does NOT use the locker; its account `save(...)` writes are isolated per-account and don't need pairing. No change.
  - **Future multi-instance deployment** — a follow-up change can enable `bank.transfer.locker=db` in the dev profile and verify with a second JVM. Out of scope here.
- **Backwards compat**: zero — `matchIfMissing = true` on `JvmAccountLocker`'s `@ConditionalOnProperty` means an application with no `bank.transfer.locker` property continues to construct the JVM locker, identical to today's behaviour.
- **Operational notes**:
  - **Choosing the strategy**: `jvm` for single-instance deployment (lowest latency — pure-in-memory locking, no DB round-trip per transfer). `db` for any deployment with >1 application instance OR any case where the DB row-lock is the canonical "source of truth" for serialisation (e.g. you also need to serialise against a manual `UPDATE` issued via the H2 console — the JVM locker doesn't see those, the DB locker does).
  - **Performance**: `JvmAccountLocker` is ~10ns per acquisition; `DbAccountLocker` is one DB round-trip per `withPairedLocks` call (~1 ms typical H2-in-memory, ~5–20 ms typical PostgreSQL over a local socket). At realistic transfer rates (≤100/s steady-state) the cost is negligible.
  - **Timeout behaviour**: identical SLA — both implementations honour `bank.transfer.lock-wait-ms` and throw the same `LockAcquisitionTimeoutException`. The JVM locker's `ReentrantLock.tryLock(ms, ...)` and the DB locker's `LOCK_TIMEOUT` setting are functionally equivalent for callers.
  - **Connection-pool hygiene** (H2): the DB locker explicitly resets `SET LOCK_TIMEOUT 0` after acquisition so the pooled connection doesn't carry a custom timeout into the next user's session. For PostgreSQL, `SET LOCAL lock_timeout` is transaction-scoped and auto-resets — see design.md Decision 4 for the dialect handling.
