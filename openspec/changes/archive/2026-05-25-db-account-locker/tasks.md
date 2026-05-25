## 1. Properties — strategy switch + validation

- [x] 1.1 Modify `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/TransferLockingProperties.java`. Extend the record from `TransferLockingProperties(long lockWaitMs)` to `TransferLockingProperties(long lockWaitMs, String strategy)`. Add a compact constructor that:
  - Normalises `strategy` to lowercase, defaulting to `"jvm"` if `null` or blank.
  - Rejects any value other than `"jvm"` or `"db"` with `IllegalArgumentException("bank.transfer.locker must be 'jvm' or 'db' (was: '" + originalValue + "')")`.
  - The `lockWaitMs` validation (if any) is unchanged.
- [x] 1.2 Add `infrastructure/src/test/java/com/bank/core/infrastructure/concurrency/TransferLockingPropertiesTest.java`:
  - Default strategy is `"jvm"` when null.
  - Default strategy is `"jvm"` when blank (`""`, `"  "`).
  - `"JVM"`, `"Jvm"`, `"jvm"` all normalise to `"jvm"`.
  - `"DB"`, `"Db"`, `"db"` all normalise to `"db"`.
  - `"hybrid"`, `"sql"`, `"none"`, `"redis"` each throw `IllegalArgumentException` whose message contains the literal `bank.transfer.locker must be 'jvm' or 'db' (was:` and the offending value (in its original case).
  - `lockWaitMs` round-trips unchanged across all the above.

## 2. Gate JvmAccountLocker with @ConditionalOnProperty

- [x] 2.1 Modify `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/JvmAccountLocker.java`. Add `@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "jvm", matchIfMissing = true)` immediately after the existing `@Component` annotation. Add the matching `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` import. The class body, semantics, and existing tests stay identical.

## 3. DbAccountLocker implementation

- [x] 3.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/concurrency/DbAccountLocker.java`. Final class implementing `AccountLocker`. Annotations: `@Component`, `@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "db")`.
- [x] 3.2 Field declarations:
  - `private static final Logger log = LoggerFactory.getLogger(DbAccountLocker.class)`.
  - `private final long waitMs` (from properties).
  - `private final JdbcTemplate jdbcTemplate` (injected; Spring Boot auto-configures it from the existing `DataSource`).
  - `private final String dialect` (populated lazily on first call via `Connection.getMetaData().getDatabaseProductName().toLowerCase()`, cached for the bean's lifetime — `volatile String` to be safe).
- [x] 3.3 Constructor: `public DbAccountLocker(TransferLockingProperties properties, JdbcTemplate jdbcTemplate)`. Both arguments null-checked via `Objects.requireNonNull`. Store `waitMs = properties.lockWaitMs()` and the `JdbcTemplate`.
- [x] 3.4 Public method `long waitMs()` (mirrors the JVM locker's accessor; used by diagnostic tests).
- [x] 3.5 Public method `withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction)`:
  1. `Objects.requireNonNull` all three arguments.
  2. Verify `TransactionSynchronizationManager.isSynchronizationActive()`; if false throw `IllegalStateException("paired locks require an active transaction (no synchronization registered for thread " + Thread.currentThread().getName() + ")")` — same message shape as `JvmAccountLocker`.
  3. Compute canonical ordering: if `a.equals(b)`, build `List<String> nums = List.of(a.value())`; else if `a.value().compareTo(b.value()) <= 0`, `nums = List.of(a.value(), b.value())`; else `nums = List.of(b.value(), a.value())`. Also derive `AccountNumber first` and `AccountNumber second` for the `LockAcquisitionTimeoutException` to throw.
  4. Set the database lock timeout for the upcoming FOR UPDATE: call a private `setLockTimeout(long ms)` helper that issues the dialect-appropriate SQL (`SET LOCK_TIMEOUT <ms>` for H2, `SET LOCAL lock_timeout = '<ms>ms'` for PostgreSQL — see task 3.7). On the first call per bean lifetime, detect the dialect by running `jdbcTemplate.execute((Connection conn) -> { dialect = conn.getMetaData().getDatabaseProductName().toLowerCase(); return null; })`.
  5. Build the SQL: `"SELECT id FROM account WHERE account_number IN (" + placeholders + ") ORDER BY account_number FOR UPDATE"` where `placeholders` is `"?"` (same-account) or `"?, ?"` (two distinct).
  6. Execute via `jdbcTemplate.query(sql, ps -> { for (int i = 0; i < nums.size(); i++) ps.setString(i + 1, nums.get(i)); }, rs -> null)` (or `jdbcTemplate.queryForList(sql, UUID.class, args.toArray())` if simpler). Wrap in a try-catch on `DataAccessException`.
  7. On `DataAccessException`: unwrap to `SQLException` via `ex.getMostSpecificCause()`. If `getSQLState()` is `"HYT00"` OR `getErrorCode()` is `50200` (H2 `LOCK_TIMEOUT_1`) OR `getSQLState()` is `"55P03"` (PostgreSQL `lock_not_available`), throw `new LockAcquisitionTimeoutException(first, second, waitMs)`. Otherwise rethrow the original `DataAccessException`.
  8. Immediately after the FOR UPDATE succeeds, reset the timeout via `setLockTimeout(0)` (H2 dialect) or no-op (PostgreSQL — `SET LOCAL` auto-resets).
  9. `log.debug("acquired DB paired locks on {}, {} (waitMs={}, dialect={})", first, second, waitMs, dialect)`.
  10. Invoke `inTransaction.run()`. The DB releases locks at commit/rollback — no `TransactionSynchronization` registration.
- [x] 3.6 Private helper `setLockTimeout(long ms)`:
  - If `dialect.contains("postgresql")`: `jdbcTemplate.execute("SET LOCAL lock_timeout = '" + ms + "ms'")`.
  - Else (H2 fallback covers both `h2` and unknown): `jdbcTemplate.execute("SET LOCK_TIMEOUT " + ms)`.
- [x] 3.7 Class-level Javadoc:
  - Section "Cross-instance correctness": the DB is the single arbiter; no JVM-local state.
  - Section "Why one SQL statement": canonical-order acquisition inside one statement avoids deadlock between sequential statements (design.md Decision 1).
  - Section "Why explicit timeout reset": H2's `SET LOCK_TIMEOUT` is session-scoped and would leak into the pooled connection's next user; the reset prevents that leak (design.md Decision 2).
  - Section "Why no afterCompletion hook": the DB's transaction commit/rollback is the canonical release point; an application-side hook would be redundant and would couple the implementation to Spring's transaction synchronisation machinery.
  - Section "Dialect support": H2 and PostgreSQL; other dialects fall back to H2 SQL (best-effort; will throw on an unknown DB if the SQL isn't recognised).

## 4. Configuration files

- [x] 4.1 Modify `bootstrap/src/main/resources/application.yaml`. Under the existing `bank.transfer` block, add `locker: jvm` with a multi-line comment:
  ```yaml
  bank:
    transfer:
      # Locker strategy: 'jvm' (default, single-instance — process-wide
      # ConcurrentHashMap<AccountNumber, ReentrantLock>) or 'db' (multi-
      # instance — SELECT id FROM account ... FOR UPDATE serialises across
      # all JVMs sharing the database).
      #
      # The 'db' strategy is required for any deployment with more than one
      # application instance behind a load balancer. 'jvm' is faster
      # (~10 ns per acquisition vs ~1 ms DB round-trip on H2-in-memory)
      # but only correct under single-instance topology.
      #
      # Both strategies honour bank.transfer.lock-wait-ms (default 5000)
      # and throw the same LockAcquisitionTimeoutException on timeout.
      locker: jvm
      lock-wait-ms: 5000
      ...
  ```
  Preserve existing keys (`lock-wait-ms` etc.) under the `transfer` block; the `locker` key is additive.
- [x] 4.2 No change to `bootstrap/src/test/resources/application-test.yaml`. Tests continue to exercise the JVM strategy by default; tests that want the DB strategy opt in per-class via `@TestPropertySource`.

## 5. Rename and re-annotate the existing F07 integration test

- [x] 5.1 Rename `bootstrap/src/test/java/com/bank/core/concurrency/AccountLockerIntegrationTest.java` to `JvmAccountLockerIntegrationTest.java`. Update the class name inside the file to match.
- [x] 5.2 Add `@TestPropertySource(properties = "bank.transfer.locker=jvm")` to the class (explicit, even though it matches the default) so the test's intent is obvious in reports and so the test still passes after the default ever changes.
- [x] 5.3 The existing test assertions, helpers, and scenarios are unchanged. Verify the rename didn't break the build via `./gradlew :bootstrap:test --tests "com.bank.core.concurrency.JvmAccountLockerIntegrationTest"`.

## 6. DbAccountLockerIntegrationTest

- [x] 6.1 Create `bootstrap/src/test/java/com/bank/core/concurrency/DbAccountLockerIntegrationTest.java`:
  - `@SpringBootTest` with `@ActiveProfiles("test")` and `@TestPropertySource(properties = {"bank.transfer.locker=db", "spring.datasource.url=jdbc:h2:mem:bankcore-dblock-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})` (per-class unique H2 URL to avoid contention with other tests' row locks).
  - `@Autowired AccountLocker locker` (no specific type — let Spring inject whichever is wired).
  - `@Autowired PlatformTransactionManager txManager` for `TransactionTemplate`.
  - `@Autowired Accounts accounts` for fixture seeding via `accounts.save(...)`.
- [x] 6.2 Test `correctImplementationIsWired`:
  - Assert `locker instanceof DbAccountLocker`.
  - Assert `applicationContext.getBeansOfType(JvmAccountLocker.class).isEmpty()`.
- [x] 6.3 Test `pairedLocksHeldUntilCommit`:
  - Seed two accounts A and B at zero balance.
  - Use `TransactionTemplate.execute(...)` to call `locker.withPairedLocks(A, B, () -> { ... })`. Inside the runnable, spawn a thread that attempts a separate JDBC connection and runs `SELECT ... FOR UPDATE NOWAIT` against the same `account_number IN (A, B)` row set; assert that the separate thread's attempt fails with the H2 lock timeout SQLSTATE within ~200 ms (i.e. the row is in fact locked by the main thread).
  - After the transaction commits, assert that the same probe SELECT succeeds immediately from a separate connection.
- [x] 6.4 Test `oppositeDirectionConcurrentCallsDoNotDeadlock`:
  - Seed accounts A and B.
  - Spawn 50 threads, half calling `locker.withPairedLocks(A, B, runnable)` and half calling `locker.withPairedLocks(B, A, runnable)`, each wrapping the call in its own `TransactionTemplate.execute(...)`. The runnable performs a synthetic `AtomicLong` increment on a shared per-account counter map.
  - With `bank.transfer.lock-wait-ms=500` (the test profile default), assert all 50 calls complete within 10 seconds, zero `LockAcquisitionTimeoutException` is thrown, and the synthetic counters match expected totals.
- [x] 6.5 Test `crossPairContentionSerialises`:
  - Seed accounts A, B, C.
  - Run call C1: `locker.withPairedLocks(A, B, ...)` performing a synthetic adjustment to A's counter.
  - Concurrently run C2: `locker.withPairedLocks(C, A, ...)` performing another adjustment to A's counter.
  - Assert both calls complete, no deadlock, A's final synthetic balance is the deterministic sum.
- [x] 6.6 Test `timeoutMapsToLockAcquisitionTimeoutException`:
  - Seed accounts A and B.
  - Spawn thread T1 that acquires `withPairedLocks(A, B, runnable)` and inside the runnable sleeps for 2 seconds.
  - On the main thread, after ~100 ms (enough for T1 to be inside the runnable), call `withPairedLocks(A, B, ...)` with `bank.transfer.lock-wait-ms=200`. Assert `LockAcquisitionTimeoutException` is thrown carrying `firstAccount() == min(A, B)`, `secondAccount() == max(A, B)`, and `waitMs() == 200`. Join T1.
- [x] 6.7 Test `outOfTransactionCallRejected`:
  - Call `locker.withPairedLocks(A, B, runnable)` directly (no `TransactionTemplate` wrapping). Assert `IllegalStateException` is thrown with a message containing `paired locks require an active transaction`.
- [x] 6.8 Test `sameAccountCallLocksOnce`:
  - Seed account A.
  - Call `locker.withPairedLocks(A, A, runnable)` inside a `TransactionTemplate`. Assert the runnable executes exactly once.
  - Enable SQL TRACE for one statement and assert the `IN (?)` clause contained exactly one entry — or use a `JdbcTemplate.queryForList` probe to inspect the `account` row count and confirm the same row was touched once.
- [x] 6.9 Test `missingAccountIsTransparent`:
  - Seed only account A; do NOT seed `B-MISSING`.
  - Call `locker.withPairedLocks(A, AccountNumber.of("B-MISSING"), runnable)` inside a `TransactionTemplate`. Assert the runnable executes; assert no exception is thrown by the locker itself.

## 7. LockerStrategyWiringTest (small focused test)

- [x] 7.1 Create `bootstrap/src/test/java/com/bank/core/concurrency/LockerStrategyWiringTest.java`. The class uses two nested `@SpringBootTest`-annotated inner classes (or two separate classes) — each verifies one strategy without polluting the other's context.
- [x] 7.2 Inner class `JvmStrategyTest` annotated `@SpringBootTest`, `@TestPropertySource(properties = "bank.transfer.locker=jvm")`:
  - Asserts `context.getBean(AccountLocker.class) instanceof JvmAccountLocker`.
  - Asserts `context.getBeansOfType(DbAccountLocker.class).isEmpty()`.
- [x] 7.3 Inner class `DbStrategyTest` annotated `@SpringBootTest`, `@TestPropertySource(properties = "bank.transfer.locker=db")`:
  - Asserts `context.getBean(AccountLocker.class) instanceof DbAccountLocker`.
  - Asserts `context.getBeansOfType(JvmAccountLocker.class).isEmpty()`.
- [x] 7.4 Inner class `InvalidStrategyTest` (optional — context fails to start, so use `ApplicationContextRunner` or `SpringApplicationBuilder.run(...)` inside `assertThrows(Throwable.class, ...)`) verifies that `bank.transfer.locker=hybrid` causes context startup to fail with a root cause naming `bank.transfer.locker must be 'jvm' or 'db'`.

## 8. F06 atomicity test under the DB strategy

- [x] 8.1 Create `bootstrap/src/test/java/com/bank/core/persistence/transfer/TransferAtomicityIntegrationDbLockerTest.java`. Either extends the existing `TransferAtomicityIntegrationTest` with a different `@TestPropertySource` (preferred — avoids duplication of test bodies) OR is a copy with the property override. Either way: `@TestPropertySource(properties = {"bank.transfer.locker=db", "spring.datasource.url=jdbc:h2:mem:bankcore-dblock-atomic-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})`.
- [x] 8.2 Confirm the existing atomicity scenarios (happy-path transfer, source insufficient funds rollback, destination suspended rollback, etc.) all pass when F06 is wired against `DbAccountLocker`. No new assertions needed — the point is to prove the strategy switch is a transparent swap for F06.

## 9. ArchUnit verification

- [x] 9.1 Confirm the existing `ModuleBoundaryTest.noClassesOutsideConcurrencyAdapterMayUseReentrantLock` and `noClassesOutsideConcurrencyAdapterMayUseTransactionSynchronizationManager` rules still pass. `DbAccountLocker` lives in the allowed package and uses `TransactionSynchronizationManager.isSynchronizationActive()` (allowed) but not `ReentrantLock`.
- [x] 9.2 No new ArchUnit rule needed — the existing confinement already covers both implementations.

## 10. End-of-change verification

- [x] 10.1 Run `./gradlew clean build`. All modules green; F00 ArchUnit suite still passes; no new Gradle dependency.
- [x] 10.2 Run `openspec change validate db-account-locker --strict`. Confirm clean.
- [x] 10.3 Run `openspec validate --specs`. All 12 capability specs validate.
- [ ] 10.4 Manual smoke (JVM strategy): `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'`. Captured stdout contains a line for the wired `JvmAccountLocker` bean; F09 seed succeeds; `POST /api/v1/transfers` succeeds.
- [ ] 10.5 Manual smoke (DB strategy): `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev --bank.transfer.locker=db'`. F09 seed succeeds; `POST /api/v1/transfers` succeeds; captured stdout shows `DbAccountLocker` debug-level lock acquisition lines (if log level is DEBUG for `com.bank.core.infrastructure.concurrency`).
- [ ] 10.6 Manual smoke (invalid strategy): `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev --bank.transfer.locker=hybrid'`. Application context startup fails with a root cause naming `bank.transfer.locker must be 'jvm' or 'db' (was: 'hybrid')`.
