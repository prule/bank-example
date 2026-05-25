## Context

`AccountLocker` (the application-layer port) was always shaped to abstract the choice of implementation — F07's Javadoc names a future "DB-row-lock adapter" explicitly, and `openspec/config.yaml`'s `multi-instance` open decision tracks the path. Today's only implementation, `JvmAccountLocker`, uses a process-wide `ConcurrentHashMap<AccountNumber, ReentrantLock>` plus a `TransactionSynchronization.afterCompletion` release callback. It is correct for single-instance deployment and demonstrably so (the F07 contention tests pass with 100-way concurrency).

Multi-instance deployment defeats it. Two JVMs talking to the same database both canonicalise lock order within their own `ConcurrentHashMap` but the maps are independent — so two opposite-direction transfers can deadlock at the database level (each JVM has acquired its application-level lock and waits on the other's DB row write) or, worse, race past the application-level guard entirely and corrupt the cached `account.balance` column.

A DB-backed locker fixes this by moving the canonical-order serialisation point into the database itself. `SELECT ... FOR UPDATE` against the `account` table acquires row-level exclusive locks that the DB holds until the surrounding transaction commits or rolls back. Every JVM contends for the same row; the DB is the single arbiter. The same property already governs the JVM locker's wait bound (`bank.transfer.lock-wait-ms`) maps cleanly to the DB's `LOCK_TIMEOUT` / `lock_timeout` setting, so the caller-visible timeout SLA is unchanged.

What changes from F07's architecture:

- A second implementation joins `JvmAccountLocker` in the same package.
- A `String strategy` field on `TransferLockingProperties` selects between them.
- Both implementations are `@ConditionalOnProperty`-gated so exactly one is constructed per context.
- The release mechanism differs: JVM locker uses an `afterCompletion` Spring hook; DB locker relies on the DB's transaction commit. Caller-visible behaviour is identical.

Stakeholders:

- The operator deciding deployment topology — picks the strategy at boot.
- The application developer writing F06 / F08 / future use cases — sees no change. The port abstraction holds.
- The on-call investigating a `LockAcquisitionTimeoutException` — sees the same exception shape regardless of strategy.

Constraints inherited:

- F00 ArchUnit boundary rules confine `ReentrantLock` and `TransactionSynchronizationManager` to `com.bank.core.infrastructure.concurrency..`. The new `DbAccountLocker` lives in the same package and uses `TransactionSynchronizationManager.isSynchronizationActive()` for the active-transaction guard, so the rules still hold.
- F07 spec "Single lock-acquisition component" forbids paired-lock acquisition outside the locker package. The two implementations are both inside the package; the spec is honoured.

## Goals / Non-Goals

**Goals:**

- A `DbAccountLocker` that uses `SELECT ... FOR UPDATE` for row-level exclusive locking in canonical order via a single SQL statement.
- A runtime switch (`bank.transfer.locker`, default `jvm`) that selects exactly one implementation per Spring context.
- Behavioural parity for every existing `transfer-locking` scenario: canonical order, deadlock-freedom under opposite-direction concurrency, cross-pair correctness, timeout-as-`LockAcquisitionTimeoutException`, out-of-transaction rejection, same-account-locks-once.
- A `TransferLockingProperties` change that validates the strategy value at bean construction so misconfigurations fail at startup.
- Test coverage that mirrors the existing JVM-locker integration test suite for the DB implementation, plus a strategy-switch wiring test.

**Non-Goals:**

- A multi-JVM contention test (would require spawning a second JVM in the test suite or a TestContainers Postgres deployment — out of scope; the DB's MVCC + row-lock semantics are well-tested upstream).
- Switching the default to `db`. The default stays `jvm` for back-compat and because single-instance is the realistic deployment for this codebase today.
- An auto-detect mechanism that picks the strategy based on the deployment shape (e.g. profile, clustered presence). Manual configuration is clearer.
- A "hybrid" locker that tries JVM first and falls back to DB. The mutually-exclusive contract simplifies reasoning.
- Supporting any DB beyond H2 and PostgreSQL. Other dialects can be added per-need with a SQLSTATE table.
- An HTTP endpoint to switch strategies at runtime. The strategy is set at boot via `application*.yaml` or `--bank.transfer.locker=...` on the command line, mirroring every other config knob in the project.

## Decisions

### Decision 1: One SQL statement per `withPairedLocks` call

The DB locker issues exactly ONE `SELECT id FROM account WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE` per call (or `IN (?)` for the same-account case). Two separate `SELECT ... FOR UPDATE` statements (one per account) would NOT be wrong but would risk a subtle interleaving: between the two statements, another transaction could acquire the second account's lock and then wait on the first, producing a classic two-step deadlock that the DB might not detect quickly.

**Why one statement:**

- The single statement with `ORDER BY` followed by `FOR UPDATE` is honoured by both H2 and PostgreSQL: the rows are locked in the `ORDER BY` order within that statement. The DB never observes "we hold A, now we want B" — it observes "we want both, please lock them in this order".
- Round-trip cost is halved (one network round-trip instead of two for the PostgreSQL case).
- The DB's deadlock detector is naturally bypassed because lock acquisition is atomic from its perspective.

**Alternatives considered:**

- Two sequential `SELECT a FOR UPDATE` then `SELECT b FOR UPDATE`. Rejected per the deadlock argument above.
- A stored procedure that takes both account numbers and returns after locking. Rejected — adds DB-specific deployment surface; the IN-clause approach is portable.
- Manually acquiring an advisory lock per account (`pg_advisory_xact_lock`) keyed by `hashtext(account_number)`. Rejected — H2 doesn't have an equivalent. The DB-locker's correctness story is "row locks on the actual `account` row"; advisory locks would force callers to understand a second locking mechanism on top.

### Decision 2: Lock-timeout via `SET LOCK_TIMEOUT N` per call, reset to `0` after acquisition

The DB locker issues `SET LOCK_TIMEOUT <waitMs>` immediately before the `SELECT ... FOR UPDATE` and `SET LOCK_TIMEOUT 0` immediately after. The first call constrains the lock wait to the configured budget; the second restores the connection's default so subsequent statements inside `inTransaction.run()` (and any later user of the pooled connection) don't inherit our custom timeout.

**Why explicit reset:**

- H2's `SET LOCK_TIMEOUT` is session-scoped — the value persists for the connection. With Hikari's connection pool, a connection returned to the pool with a non-default timeout would silently affect the next user (e.g. a long-running Flyway migration in the test profile would unexpectedly time out at the F07 wait bound).
- The two `SET` statements add ~1 ms each on H2-in-memory; negligible compared to network or DB-on-disk latency. Worth the predictability.

**Why `LOCK_TIMEOUT 0` to reset:**

- H2's `LOCK_TIMEOUT 0` means "no wait — fail immediately if not granted" for *subsequent* lock attempts. By the time we reset, all our locks are already acquired, so the value applies only to whatever the runnable does next. If the runnable's own statements happen to need locks (e.g. an `UPDATE account` issued by F06's `accounts.save(...)`), they'll get `LOCK_TIMEOUT 0` semantics — meaning "fail immediately on contention". For the F06 path, this is fine because we already hold the row's X-lock, so no contention is possible on that row; any other row touched by `inTransaction.run()` (the `journal_entry` insert, the `ledger_movement` inserts) is being created, not contended.
- An alternative would be to reset to the H2 default (`SELECT LOCK_TIMEOUT_DEFAULT` is not a thing) or to whatever the connection had before — but reading that out adds another round-trip per call. The `0` reset is the simplest predictable choice given F06's well-known write pattern.

**PostgreSQL dialect note:**

- PostgreSQL's `SET LOCAL lock_timeout = '<ms>ms'` is transaction-scoped and auto-resets at commit. No explicit reset needed. The adapter detects the dialect via the JDBC URL or `connection.getMetaData().getDatabaseProductName()` and issues the appropriate variant. This dialect-switch is a one-liner inside the adapter and is documented in the class-level Javadoc.

**Alternatives considered:**

- Use JDBC's `Statement.setQueryTimeout(int seconds)`. Rejected — H2 interprets this as a total-statement-time bound, not a lock-wait bound, so a successful FOR UPDATE that takes longer than the timeout would still fail.
- Set the timeout once per connection at Hikari pool init via a `connectionInitSql` hook. Rejected — would apply to every statement on every connection, defeating the purpose of having an opt-in DB-locker strategy.

### Decision 3: Strategy switch via `@ConditionalOnProperty`, not a `@Bean` factory or `@Profile`

Each locker bean is annotated `@Component @ConditionalOnProperty("bank.transfer.locker", havingValue = "<jvm|db>")`. The JVM locker carries `matchIfMissing = true` so the default stays JVM with no property set. Spring's conditional bean resolution constructs exactly one.

**Why `@ConditionalOnProperty`, not `@Bean` factory:**

- A `@Bean` factory method in `BankCoreApplication` would centralise the conditional logic in one place but requires the use case to know which Spring profile / property combination wins. The annotation approach localises the decision next to each implementation — the implementation declares "I'm the JVM strategy" / "I'm the DB strategy" itself.
- Mirrors the F09 (`SeedData`) pattern exactly — that change also uses `@ConditionalOnProperty` for strategy-style bean gating.

**Why `@ConditionalOnProperty`, not `@Profile`:**

- Profiles are for environment-wide configuration (`dev`, `test`, `default`). The strategy is orthogonal to environment — a `dev` profile can run either locker; production should generally run JVM but might want DB for a clustered POC. Property-based switching is cleaner.

**Why not a runtime if-else inside a single bean:**

- A single bean with `if (strategy == "db") { ... } else { ... }` per call adds branching overhead, duplicates injection (both `JdbcTemplate` and `ConcurrentHashMap<...>` would be fields), and obscures which path is live in the actuator `/beans` endpoint. Two beans with conditional construction give one bean per choice in the actuator and one set of fields per implementation.

### Decision 4: Strategy validation at construction, not at injection

`TransferLockingProperties` compact constructor normalises and validates `strategy`. Invalid values fail the application context build with a clear message. This is preferable to:

- **Validating in each locker bean** — each implementation would have to know the other's name, and the failure would happen during conditional evaluation (a more obscure place in the Spring startup log).
- **Letting Spring silently pick neither** — if neither locker's `havingValue` matches, the application context would have no `AccountLocker` bean and F06's use case would fail at bean wiring with `NoSuchBeanDefinitionException` — a confusing error for a typo'd property.
- **Defaulting to JVM on any invalid value** — a fat-fingered `db` (`bd`, `db ` with trailing space, `database`) would silently fall back to JVM. Operators expecting DB-strategy behaviour would not notice until the multi-instance issue bit them.

The chosen approach: normalise to lowercase, accept only `{"jvm", "db"}`, throw `IllegalArgumentException` with the offending value otherwise. Spring's startup wraps the throw in a clear "Failed to bind properties" error pointing at `bank.transfer.locker`.

### Decision 5: SQLSTATE-to-exception mapping is implementation-specific but defensive

The DB locker catches `DataAccessException` from the `FOR UPDATE` statement, unwraps to `SQLException`, and inspects `getSQLState()`. Known lock-timeout codes:

- H2: `HYT00` (statement timeout) and the specific H2 error code `LOCK_TIMEOUT_1 = 50200` accessible via `e.getErrorCode()`.
- PostgreSQL: `55P03` (lock_not_available).

The adapter matches against a small set: `{"HYT00", "55P03"}` plus the H2 error code 50200. Any match → `LockAcquisitionTimeoutException(first, second, waitMs)`. Any other `DataAccessException` propagates unchanged — it's likely a genuine schema/integrity issue and conflating it with a timeout would hide real bugs.

**Why not catch all `DataAccessException` and treat as timeout:**

- An `IntegrityConstraintViolationException` (e.g. someone dropped the unique index on `account_number` mid-transaction) is not a timeout; converting it to one would mislead the operator.
- A `DataAccessResourceFailureException` (connection dropped) is not a timeout; the F06 use case should propagate it so the F03 global handler can produce a `503` envelope, not a `409` derived from `LockAcquisitionTimeoutException`.

The narrow match keeps the timeout exception's semantic precision.

### Decision 6: Test strategy — duplicate the existing F07 contention suite for the DB locker

`AccountLockerIntegrationTest` (today's F07 test) gets renamed to `JvmAccountLockerIntegrationTest` and annotated `@TestPropertySource(properties = "bank.transfer.locker=jvm")`. A new `DbAccountLockerIntegrationTest` runs the same six scenarios with `bank.transfer.locker=db`. The two tests use slightly different probe code (the DB-locker scenario for "lock held until commit" reads via a separate JDBC connection rather than a separate thread's `tryLock` attempt) but assert the same observable outcomes.

**Why duplicate, not parametrise:**

- A `@ParameterizedTest` factory would have to switch the active Spring context per parameter, which Spring's test framework does by re-building the context — expensive and brittle.
- Two test classes give clearer test reports (`JvmAccountLockerIntegrationTest > correctImplementationIsWired` vs `DbAccountLockerIntegrationTest > correctImplementationIsWired`) and let each test class apply its own `@TestPropertySource` and any class-specific setup (e.g. the DB-locker test wants `bank.transfer.lock-wait-ms=500` from the test profile but might want to override per-method).
- The duplication is ~80% identical, ~20% adapter-specific; the duplication cost is small and the clarity win is large.

**Strategy-switch wiring test** (separate small class `LockerStrategyWiringTest`): verifies that `bank.transfer.locker=jvm` constructs `JvmAccountLocker` and not `DbAccountLocker`, and vice versa. Two methods, each its own `@SpringBootTest @TestPropertySource` (different contexts).

## Risks / Trade-offs

[Risk] Setting `SET LOCK_TIMEOUT 0` on H2 after acquisition means any later contention inside `inTransaction.run()` fails immediately. → **Mitigation**: F06's write pattern is exactly "load via the held lock, then mutate and write" — there is no other contention point inside the runnable. F08's account-opening goes through F06 transitively and inherits the same pattern. Any future use case that takes additional locks inside the runnable needs to set its own timeout — documented in the class Javadoc.

[Risk] The DB locker adds one DB round-trip per `withPairedLocks` call. → **Mitigation accepted**: ~1 ms on H2-in-memory, ~5–20 ms on PostgreSQL over a local socket. At the realistic transfer rate (≤100/s steady-state) the overhead is sub-second per minute. The performance cost is the price of multi-instance correctness.

[Risk] `SET LOCK_TIMEOUT` is dialect-specific. PostgreSQL uses `SET LOCAL lock_timeout = '<ms>ms'`, H2 uses `SET LOCK_TIMEOUT <ms>`. → **Mitigation**: the adapter detects the database product via `Connection.getMetaData().getDatabaseProductName()` at first call and caches the dialect-specific SQL. Two dialects are supported (H2 and PostgreSQL); any other DB falls back to no timeout-set (logs a WARN naming the unsupported product) and relies on the application-level wait bound becoming a "best effort that may exceed the configured budget". For this codebase H2 is the only DB in scope; PostgreSQL is the realistic future production target.

[Risk] Multi-instance correctness requires the DB strategy. Operators might deploy multi-instance with the default `jvm` strategy and not notice until a corruption event. → **Mitigation accepted**: explicitly documented in proposal.md's "Operational notes". A future operational/runbook change can add a startup self-check (e.g. "if cluster size > 1 and strategy is jvm, log WARN") — out of scope here.

[Risk] The test `DbAccountLockerIntegrationTest` shares the JVM-shared `bankcore-test` H2 with other integration tests; row locks on `account` rows acquired by one test could starve another. → **Mitigation**: each test method that exercises `withPairedLocks` seeds its own uniquely-named accounts (e.g. `LOCK-TEST-A-{UUID}`) so row contention is bounded to the test's own data. The `@BeforeEach` wipe also clears any leftover rows. If contention with parallel tests becomes a problem, the integration test can switch to a per-class unique H2 URL like F11's test does.

[Risk] H2's `SELECT ... ORDER BY ... FOR UPDATE` behaviour might differ from PostgreSQL in edge cases (e.g. row visibility under READ_COMMITTED). → **Mitigation**: the test profile uses H2 in `MODE=PostgreSQL`, which approximates PostgreSQL semantics for the common cases. The integration tests pin observable behaviour; if PostgreSQL ever exhibits a regression, the same test against a TestContainers Postgres will catch it.

[Risk] The dialect-detection introduces an implicit branch in production code that's exercised only by manual test against PostgreSQL. → **Mitigation accepted**: the H2-only happy path is well-covered. The PostgreSQL branch is a one-line conditional (`if (productName.contains("PostgreSQL")) { ... }`) tested via a manual smoke step in tasks.md; a follow-up TestContainers Postgres test is the proper remediation.

## Open Questions

None — the change is bounded to the locker package, the property switch is well-understood, and the spec already abstracted the choice via the port.
