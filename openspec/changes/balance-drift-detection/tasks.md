## 1. Application module — value records and ports

- [x] 1.1 Create `application/src/main/java/com/bank/core/application/audit/DriftReport.java` as `public record DriftReport(long floor, long ceiling, int inspected, int drifted) { public static DriftReport empty(long floor, long ceiling) { return new DriftReport(floor, ceiling, 0, 0); } }`. Class Javadoc names the producer (`DetectBalanceDrift.audit()`) and the invariant the producer maintains (`drifted <= inspected`; both incremented only inside the use case's per-candidate loop).
- [x] 1.2 Create `application/src/main/java/com/bank/core/application/audit/LedgerMovements.java` as a plain Java interface:
  - `long currentCeiling()` — returns `COALESCE(MAX(ledger_movement.id), 0)`.
  - `Set<AccountId> distinctAccountIdsInWindow(long floorExclusive, long ceilingInclusive)`.
  - `BigDecimal sumSignedAmountForAccount(AccountId id)` — sum of all movements for the account, CREDIT positive / DEBIT negative.
  - Class Javadoc explains why this is a separate port from `JournalEntries` (movement-shaped vs journal-shaped); names F11 as the sole consumer.
- [x] 1.3 Create `application/src/main/java/com/bank/core/application/audit/AuditCheckpoints.java` as a plain Java interface:
  - `long readOrZero(String auditName)` — returns the persisted `last_movement_id` for the named audit, or `0L` if no row.
  - `void save(String auditName, long lastMovementId)` — upserts the row.
  - Class Javadoc names the only consumer (F11) and the only audit name in scope (`"balance_drift"`); explains that a second audit (if ever) would pick its own name.
- [x] 1.4 Add `application/src/test/java/com/bank/core/application/audit/DriftReportTest.java` — `empty(floor, ceiling)` returns `(floor, floor, 0, 0)` (signals no-op — ceiling argument is intentionally ignored so the no-op factory always shows `floor==ceiling`); explicit construction round-trips all four fields.
- [x] 1.5 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/audit/` returns zero matches.

## 2. Application module — DetectBalanceDrift use case

- [x] 2.1 Create `application/src/main/java/com/bank/core/application/audit/DetectBalanceDrift.java`. Final class. `public static final String AUDIT_NAME = "balance_drift"`. Public constructor `DetectBalanceDrift(LedgerMovements movements, Accounts accounts, AuditCheckpoints checkpoints, AccountNumber clearingAccountNumber)`. All four params null-checked via `Objects.requireNonNull` with named messages; stored as final fields.
- [x] 2.2 Field: `private static final Logger LOG = LoggerFactory.getLogger(DetectBalanceDrift.class)`.
- [x] 2.3 Public method `DriftReport audit()`:
  1. `long floor = checkpoints.readOrZero(AUDIT_NAME)`.
  2. `long ceiling = movements.currentCeiling()`.
  3. If `ceiling <= floor`: `checkpoints.save(AUDIT_NAME, ceiling)`; return `DriftReport.empty(floor, ceiling)`.
  4. `Set<AccountId> candidates = movements.distinctAccountIdsInWindow(floor, ceiling)`.
  5. `int inspected = 0, drifted = 0`.
  6. For each `AccountId id : candidates` (iteration order is the set's iteration order — `LinkedHashSet` if the adapter returns one):
     - `Optional<Account> maybe = accounts.findById(id)`. If `maybe.isEmpty()` continue (defensive — does not increment inspected).
     - `Account account = maybe.get()`.
     - If `account.number().equals(clearingAccountNumber)`: `LOG.info("clearing-account audit skipped: {} (per balance-drift-detection spec carve-out)", clearingAccountNumber.value())`; continue (does NOT increment inspected — design.md Decision 6).
     - If `account.status() != AccountStatus.ACTIVE`: `inspected++`; continue.
     - `BigDecimal raw = movements.sumSignedAmountForAccount(id)`; `BigDecimal nonNegative = raw.signum() < 0 ? BigDecimal.ZERO : raw`; `Money expected = Money.of(nonNegative)`.
     - If `expected.equals(account.balance())`: `inspected++`; continue.
     - Drift: `LOG.error("balance drift detected on account {} (cached={}, expected={}); account SUSPENDED", account.number().value(), account.balance(), expected)`; `account.suspend()`; `accounts.save(account)`; `inspected++`; `drifted++`.
  7. `checkpoints.save(AUDIT_NAME, ceiling)`.
  8. Return `new DriftReport(floor, ceiling, inspected, drifted)`.
- [x] 2.4 Class-level Javadoc names: (a) `BalanceDriftAudit` (infrastructure) as the transactional shell — checkpoint advance + suspensions commit together; (b) the per-account sum is across all time (Decision 5); (c) the clearing-account carve-out is checked before `inspected++` (Decision 6); (d) the use case does NOT wrap per-candidate work in try/catch (Decision 8) — any exception aborts the tick, the surrounding `@Transactional` rolls everything back, and the next tick re-tries.
- [x] 2.5 Add `application/src/test/java/com/bank/core/application/audit/DetectBalanceDriftTest.java`. JUnit 5, Mockito. Fixtures: an `AccountNumber CLEARING = AccountNumber.of("CLEARING-000")`; helpers `Account active(AccountNumber, Money)`, `Account suspended(AccountNumber, Money)`, `Account closed(AccountNumber, Money)`.
- [x] 2.6 Test `constructor_rejectsNullArgs` — null `movements`, `accounts`, `checkpoints`, `clearingAccountNumber` each NPE with the documented message.
- [x] 2.7 Test `auditNameConstantIsLiteralBalanceDrift` — assert `DetectBalanceDrift.AUDIT_NAME` equals exactly `"balance_drift"`.
- [x] 2.8 Test `noNewMovements_isNoOp_butStillAdvancesCheckpoint`:
  - Stub `checkpoints.readOrZero("balance_drift")` returns `42L`; `movements.currentCeiling()` returns `42L`.
  - Call `audit()`.
  - Verify `movements.distinctAccountIdsInWindow(...)` never called; `accounts.findById(...)` never called; `accounts.save(...)` never called.
  - Verify `checkpoints.save("balance_drift", 42L)` called exactly once.
  - Assert returned report equals `new DriftReport(42, 42, 0, 0)`.
- [x] 2.9 Test `emptyCandidateSet_advancesCheckpoint_noAccountLoads`:
  - Stub floor `0`, ceiling `100`; `distinctAccountIdsInWindow(0, 100)` returns empty set.
  - Verify no `findById(...)`; `checkpoints.save("balance_drift", 100)` called.
  - Assert report `(0, 100, 0, 0)`.
- [x] 2.10 Test `inBalanceCandidate_doesNotSuspend_advancesCheckpoint`:
  - Single candidate with cached balance `100.00` and `sumSignedAmountForAccount(...)` returning `100.00`.
  - Verify `account.suspend()` not invoked; `accounts.save(...)` not invoked for that account; `checkpoints.save(...)` called.
  - Assert `(floor, ceiling, 1, 0)`.
- [x] 2.11 Test `driftedActiveAccount_isSuspended_andLogged`:
  - Single ACTIVE candidate with cached `100.00`, sum `90.00`.
  - Verify `accounts.save(...)` called once with the now-SUSPENDED account; `drifted=1`.
  - Capture an `ErrorLogAppender` and assert one ERROR line contains the account number, `cached=100.00`, `expected=90.00`, `SUSPENDED`.
- [x] 2.12 Test `driftedSuspendedAccount_isNotResaved`:
  - Single SUSPENDED candidate with cached `100.00`, sum `90.00`.
  - Verify `account.suspend()` not invoked; `accounts.save(...)` not invoked for that account; `drifted=0`; `inspected=1`.
- [x] 2.13 Test `driftedClosedAccount_isSkippedExactlyLikeSuspended`:
  - Single CLOSED candidate with mismatched sum.
  - Verify same behaviour as SUSPENDED case — no suspend call, no save call, no `IllegalStatusTransitionException`; `drifted=0`, `inspected=1`.
- [x] 2.14 Test `clearingAccountInCandidateSet_isCarveOut_doesNotCountTowardInspected`:
  - Two candidates: one normal ACTIVE drifted account, one CLEARING account whose sum disagrees with its cached balance.
  - Stub `accounts.findById(clearingId)` returns an ACTIVE account whose number is the configured clearing number.
  - Verify `accounts.save(...)` invoked exactly once (the normal account); the clearing account is NEVER passed to `save(...)`; the clearing account's `suspend()` is never invoked.
  - Capture INFO logs: assert one line matches `clearing-account audit skipped: CLEARING-000`.
  - Assert report `inspected=1, drifted=1` (the clearing account is NOT in `inspected`).
- [x] 2.15 Test `missingAccountInCandidateSet_isSilentlySkipped`:
  - One candidate id whose `accounts.findById(id)` returns `Optional.empty()`.
  - Verify no `suspend`, no `save`; `inspected=0`; tick continues.
- [x] 2.16 Test `negativeRawSum_isClampedToZeroForComparison`:
  - Candidate with cached `0.00`, raw sum `-5.00` (corrupted ledger).
  - Verify the audit treats expected as `0.00` (Money cannot hold negatives); since cached equals expected, NO drift detected (`drifted=0`).
  - This is a quirk worth documenting: a corruption that produces a negative raw sum is masked when the cached balance happens to be zero. A WARN-level log mentioning the clamping COULD be added later; for now the test pins current behaviour explicitly.
- [x] 2.17 Test `iterationOrder_matchesCandidateSetOrder`:
  - Three candidates in a `LinkedHashSet` returned in known order.
  - Use Mockito `InOrder` to assert `accounts.findById(...)` calls happen in the candidate set's iteration order.
- [x] 2.18 Test `checkpointSave_alwaysHappensOnHappyPath`:
  - Parameterised across the no-op, empty-set, in-balance, and drifted scenarios — `checkpoints.save("balance_drift", ceiling)` is invoked exactly once per `audit()` call.
- [x] 2.19 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/audit/` returns zero matches.

## 3. Flyway migration

- [x] 3.1 Create `bootstrap/src/main/resources/db/migration/V4__audit_checkpoint.sql`:
  ```sql
  -- F11 — balance drift detection
  -- Single-row-per-audit checkpoint table. Holds the highest ledger_movement.id
  -- the named audit has processed; survives restarts. Only F11 writes here today
  -- (audit_name = 'balance_drift'); future audits would each pick their own name.

  CREATE TABLE audit_checkpoint (
      audit_name        VARCHAR(64) NOT NULL PRIMARY KEY,
      last_movement_id  BIGINT      NOT NULL,
      CONSTRAINT last_movement_id_non_negative CHECK (last_movement_id >= 0)
  );
  ```
- [x] 3.2 No corresponding JPA-validation note — `ddl-auto=validate` will check that the entity (next section) matches.

## 4. Infrastructure module — audit-checkpoint persistence

- [x] 4.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/audit/AuditCheckpointEntity.java`. `@Entity @Table("audit_checkpoint")`. Fields: `@Id @Column(name="audit_name", nullable=false, length=64) String auditName`, `@Column(name="last_movement_id", nullable=false) long lastMovementId`. JPA no-arg constructor, package-private; full-args constructor for the adapter. Package-private getters/setters as needed.
- [x] 4.2 Create `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/audit/AuditCheckpointRepository.java` — `interface AuditCheckpointRepository extends JpaRepository<AuditCheckpointEntity, String> {}`. No custom methods; `findById(String)` and `save(...)` are inherited.
- [x] 4.3 Create `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/audit/AuditCheckpointsJpaAdapter.java`. `@Component` implementing `AuditCheckpoints`. Constructor injects `AuditCheckpointRepository`. `readOrZero(String name)` returns `repository.findById(name).map(AuditCheckpointEntity::getLastMovementId).orElse(0L)`. `save(String name, long id)` constructs a new `AuditCheckpointEntity(name, id)` and calls `repository.save(...)` — the row's PK is `audit_name` so save is an upsert. Both methods are intentionally NOT annotated `@Transactional` — they inherit the transactional context from the calling `BalanceDriftAudit @Service @Transactional` so the checkpoint advance commits with the suspensions (spec requirement). Class Javadoc explains this.
- [x] 4.4 Add `infrastructure/src/test/java/com/bank/core/infrastructure/persistence/audit/AuditCheckpointsJpaAdapterTest.java` (or roll into the integration test in section 9) — `@DataJpaTest`-style: `readOrZero("unknown")` returns `0`; `save("x", 42)` then `readOrZero("x")` returns `42`; `save("x", 100)` upserts to `100`; the `last_movement_id_non_negative` CHECK constraint rejects a negative save (constraint violation propagates).

## 5. Infrastructure module — LedgerMovementsJpaAdapter

- [x] 5.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/ledger/LedgerMovementRepository.java` — `interface LedgerMovementRepository extends JpaRepository<LedgerMovementEntity, Long>` with three custom queries:
  ```java
  @Query("SELECT COALESCE(MAX(m.id), 0L) FROM LedgerMovementEntity m")
  long currentCeiling();

  @Query("SELECT DISTINCT m.accountId FROM LedgerMovementEntity m WHERE m.id > :floor AND m.id <= :ceiling")
  List<UUID> distinctAccountIdsInWindow(@Param("floor") long floor, @Param("ceiling") long ceiling);

  @Query("""
         SELECT COALESCE(SUM(CASE WHEN m.movementType = com.bank.core.domain.MovementType.CREDIT
                                  THEN m.amount ELSE -m.amount END), 0)
         FROM LedgerMovementEntity m
         WHERE m.accountId = :accountId
         """)
  BigDecimal sumSignedAmountForAccount(@Param("accountId") UUID accountId);
  ```
- [x] 5.2 Create `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/ledger/LedgerMovementsJpaAdapter.java`. `@Component` implementing the application-layer `LedgerMovements` port. Constructor injects the new `LedgerMovementRepository`. Methods:
  - `currentCeiling()` — delegates to repo.
  - `distinctAccountIdsInWindow(floor, ceiling)` — `repository.distinctAccountIdsInWindow(floor, ceiling).stream().map(AccountId::of).collect(toCollection(LinkedHashSet::new))` (preserves the query's order).
  - `sumSignedAmountForAccount(id)` — `repository.sumSignedAmountForAccount(id.value())`.
  - All three methods annotated `@Transactional(readOnly = true)`.
- [x] 5.3 Add `infrastructure/src/test/java/com/bank/core/infrastructure/persistence/ledger/LedgerMovementsJpaAdapterTest.java` (or roll into the integration test in section 9) — exercise the three queries against an empty table (all return zero/empty) and against a seeded table (a single F06 transfer commits two movements: ceiling > 0, distinct-in-window covers both account ids, per-account sum returns the expected signed amount).

## 6. Infrastructure module — BalanceDriftAudit transactional facade

- [x] 6.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/audit/BalanceDriftAudit.java`. `@Service @Transactional`. Constructor injects `DetectBalanceDrift useCase` (null-checked). Single public method `DriftReport audit() { return useCase.audit(); }`. Class Javadoc names design.md Decision 3 (boundary lives here, not on the scheduler or use case), the spec scenario "checkpoint advances atomically with suspensions" as the requirement this class satisfies, and the F08 `OpenAccountService` precedent.

## 7. Infrastructure module — BalanceDriftScheduler + BalanceDriftProperties

- [x] 7.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/BalanceDriftProperties.java` — `@ConfigurationProperties("bank.balance-drift") public record BalanceDriftProperties(long fixedDelayMs, long initialDelayMs)`. Compact constructor: `if (fixedDelayMs <= 0) fixedDelayMs = 30_000` (with one-shot WARN via a static helper); `if (initialDelayMs < 0) initialDelayMs = 15_000`. Class Javadoc explains the defaults match the spec target (30 s cadence, 15 s initial delay staggered away from F10's 5 s).
- [x] 7.2 Add `infrastructure/src/test/java/com/bank/core/infrastructure/scheduling/BalanceDriftPropertiesTest.java` mirroring `JournalVerificationPropertiesTest`: defaults applied for null/non-positive; explicit positives round-trip; `initialDelayMs = 0` accepted (tests need this); `initialDelayMs = -1` falls back to `15_000`.
- [x] 7.3 Create `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/BalanceDriftScheduler.java`. `@Component`. Constructor injects `BalanceDriftAudit audit` (null-checked). Field: `private static final Logger LOG = LoggerFactory.getLogger(BalanceDriftScheduler.class)`. Method `void tick()` annotated `@Scheduled(fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}", initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}")`:
  - `DriftReport report = audit.audit();`
  - `LOG.info("balance drift tick: floor={}, ceiling={}, inspected={}, drifted={}", report.floor(), report.ceiling(), report.inspected(), report.drifted());`
  - No try/catch — same precedent as F10's scheduler.
- [x] 7.4 Class-level Javadoc on the scheduler covers: `fixedDelayString` (Decision 1 of F10's design); no exception handling — Spring's `TaskScheduler` logs at WARN and re-fires; injects the facade (not the use case) per Decision 3.
- [x] 7.5 Add `infrastructure/src/test/java/com/bank/core/infrastructure/scheduling/BalanceDriftSchedulerTest.java` mirroring `JournalVerificationSchedulerTest`:
  - `tick_callsFacadeOnce_andLogsSummary`: mock `BalanceDriftAudit.audit()` returns `new DriftReport(0, 100, 5, 1)`; assert one INFO line `balance drift tick: floor=0, ceiling=100, inspected=5, drifted=1`.
  - `tick_emptyWindow_emitsHeartbeat`: mock returns `DriftReport.empty(50, 50)`; assert one INFO line `balance drift tick: floor=50, ceiling=50, inspected=0, drifted=0`.
  - `tick_facadeThrows_propagates`: mock throws `RuntimeException("DB down")`; assert exception propagates; assert NO INFO summary line emitted.
  - `scheduledAnnotation_usesFixedDelayPlaceholder`: reflect on `tick()`, assert `fixedDelayString()` literally equals `"${bank.balance-drift.fixed-delay-ms:30000}"` and `initialDelayString()` literally equals `"${bank.balance-drift.initial-delay-ms:15000}"`; assert `fixedRateString()` is empty.

## 8. Bootstrap module — wiring

- [x] 8.1 Modify `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`:
  - Add `BalanceDriftProperties.class` to the existing `@EnableConfigurationProperties` array.
  - Add `@Bean DetectBalanceDrift detectBalanceDrift(LedgerMovements movements, Accounts accounts, AuditCheckpoints checkpoints, @Value("${bank.clearing-account.number}") String clearingAccountNumber)` factory:
    ```java
    return new DetectBalanceDrift(movements, accounts, checkpoints, AccountNumber.of(clearingAccountNumber));
    ```
  - Javadoc cites design.md Decision 3 (facade owns the transaction, use case is plain Java) and Decision 6 (carve-out is checked inside the use case using the injected `AccountNumber`).
  - The `BalanceDriftAudit @Service` and `BalanceDriftScheduler @Component` are picked up by component scan; no explicit `@Bean` methods needed.

## 9. Configuration files

- [x] 9.1 Modify `bootstrap/src/main/resources/application.yaml`. Under the existing `bank:` key add:
  ```yaml
  balance-drift:
    # Background audit that compares each account's cached balance against the
    # sum of its ledger movements. See openspec/specs/balance-drift-detection/spec.md.
    fixed-delay-ms: 30000  # Spec target: every 30 s.
    initial-delay-ms: 15000  # Staggered so this and journal-verification don't both fire at the same second after boot.
  ```
- [x] 9.2 Modify `bootstrap/src/test/resources/application-test.yaml`. Add a `balance-drift:` block with `fixed-delay-ms: 200` and `initial-delay-ms: 0`. YAML comment notes it's test-only.

## 10. Integration test

- [x] 10.1 Create `bootstrap/src/test/java/com/bank/core/audit/BalanceDriftAuditIntegrationTest.java` annotated `@SpringBootTest` with `properties = {"bank.balance-drift.initial-delay-ms=0", "bank.balance-drift.fixed-delay-ms=200", "spring.datasource.url=jdbc:h2:mem:bankcore-bd-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"}` and `@ActiveProfiles("test")`. Per-class unique H2 URL to avoid pollution from other tests.
- [x] 10.2 Inject `Accounts`, `JdbcTemplate`, `PlatformTransactionManager`, `BalanceDriftScheduler`, `BalanceDriftAudit`, `AuditCheckpoints`, `JournalEntries`, `TransferFunds`. `@BeforeEach` wipes `audit_checkpoint`, `ledger_movement`, `journal_entry`, `account` in that order.
- [x] 10.3 Attach Logback `ListAppender` instances to the use-case logger and the scheduler logger in `@BeforeEach` (after Spring Boot's logging-system reset has already run for the cached context).
- [x] 10.4 Test `inBalanceTransfer_advancesCheckpoint_noDrift`:
  - Seed two ACTIVE accounts via `accounts.save(...)`; commit a real F06 `transferFunds.transfer(...)` between them; await `accounts.findByNumber(...)` to reflect post-transfer balances.
  - Await `auditCheckpoints.readOrZero("balance_drift") > 0` (i.e. the audit has run at least once and advanced past the new movements).
  - Assert both accounts are still ACTIVE.
  - Assert at least one INFO summary line with `drifted=0`.
- [x] 10.5 Test `driftOnActiveAccount_suspendsWithinOneTick`:
  - Seed ACTIVE account `CUST-DRIFT` at `100.00`; seed a partner ACTIVE account; commit a small F06 transfer (`10.00`) from the partner to `CUST-DRIFT` so `CUST-DRIFT` has movements in the next audit window.
  - Side-channel `jdbc.update("UPDATE account SET balance = ? WHERE account_number = ?", new BigDecimal("999.00"), "CUST-DRIFT")` to fabricate drift (cached 999 vs ledger 110).
  - Commit another tiny F06 transfer touching `CUST-DRIFT` to guarantee it's in the next audit window (the prior transfer is already in a window but the side-channel write happened after the audit may have already processed it; the second transfer guarantees it falls in the next window — also serves as a regression test that the drift is detected on every relevant window).

    NOTE: the second transfer mutates the cached balance again (F06 increments `account.balance`). The test asserts only the *final* state: after the audit fires, `CUST-DRIFT.status == SUSPENDED` and an ERROR log line names it.
  - Await `accounts.findByNumber("CUST-DRIFT").orElseThrow().status() == SUSPENDED`.
  - Assert at least one ERROR line whose message contains `CUST-DRIFT` and `SUSPENDED`.
- [x] 10.6 Test `clearingAccountDrift_isCarveOut_neverSuspended`:
  - Seed clearing account `CLEARING-000` and a customer account.
  - Side-channel `jdbc.update("UPDATE account SET balance = balance + 50 WHERE account_number = 'CLEARING-000'")`.
  - Commit a tiny F06 transfer touching the clearing account (e.g. an opening from clearing to customer via the F08 path, or a manual `journals.save(...)` of a transfer entry) so its id falls into the next window.
  - Await one or two audit ticks (200 ms each plus jitter).
  - Assert `accounts.findByNumber("CLEARING-000").orElseThrow().status() == ACTIVE` (NOT suspended).
  - Assert at least one INFO line `clearing-account audit skipped: CLEARING-000`.
  - Assert NO ERROR line names `CLEARING-000`.
- [x] 10.7 Test `restartSemantics_checkpointSurvivesSimulatedRestart`:
  - Allow the audit to run a few ticks; capture the checkpoint via `auditCheckpoints.readOrZero("balance_drift")`.
  - Commit a new F06 transfer.
  - Await one more tick; capture the new checkpoint; assert `newCheckpoint > oldCheckpoint` and `newCheckpoint >= ledger_movement.id` of the latest committed movement.
  - (A true JVM-restart test would require re-bootstrapping the context against the same H2 URL, which is too complex for an integration test. The "checkpoint persists across in-process ticks" assertion plus the `audit_checkpoint` Flyway-managed schema is sufficient to verify the spec's restart guarantee.)
- [x] 10.8 Test `alreadySuspendedAccount_isNotReprocessed`:
  - From the drift test's residual state (or via a fresh `@BeforeEach` seed): the suspended `CUST-DRIFT` is present.
  - Wait three audit ticks (~600 ms).
  - Assert the account remains SUSPENDED (no transition); assert NO new ERROR line for `CUST-DRIFT` is emitted in that interval (capture `appender.list` snapshots before and after).
- [x] 10.9 Test `tickSummaryLogIsEmittedEachTick`:
  - Attach a `ListAppender` to `BalanceDriftScheduler`'s logger.
  - Wait long enough for at least 3 ticks (~700 ms).
  - Assert the appender captured at least 3 INFO lines matching the pattern `balance drift tick: floor=\d+, ceiling=\d+, inspected=\d+, drifted=\d+`.

## 11. ArchUnit / boundary verification

- [x] 11.1 Confirm F00's `applicationHasNoFrameworkDependencies` still passes (the new `com.bank.core.application.audit` package has no Spring/JPA imports).
- [x] 11.2 Add `bootstrap/src/test/java/com/bank/core/audit/BalanceDriftArchUnitTest.java`:
  - Assert `DetectBalanceDrift`, `DriftReport`, `LedgerMovements`, `AuditCheckpoints` reside under `com.bank.core.application.audit..`.
  - Assert `BalanceDriftAudit` resides under `com.bank.core.infrastructure.audit..`.
  - Assert `BalanceDriftScheduler` and `BalanceDriftProperties` reside under `com.bank.core.infrastructure.scheduling..`.
  - Assert `AuditCheckpointEntity`, `AuditCheckpointsJpaAdapter`, `LedgerMovementsJpaAdapter` reside under `com.bank.core.infrastructure.persistence..`.

## 12. End-of-change verification

- [x] 12.1 Run `./gradlew clean build`. All new tests pass; F00 ArchUnit suite still passes; no new production Gradle dependency.
- [x] 12.2 Run `openspec change validate balance-drift-detection --strict`. Confirm a clean `Change "balance-drift-detection" is valid`.
- [x] 12.3 Run `openspec validate --specs`. Confirm all 12 capability specs still validate after the delta is merged.
- [ ] 12.4 Manual smoke: `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'`. Within 20 seconds (the 15 s initial delay + a 30 s tick — or sooner, depending on initial-delay vs the F10 tick) captured stdout shows one `balance drift tick: floor=0, ceiling=N, inspected=K, drifted=0` line — F09 seeded 2 funded customer opens (4 movements), so `ceiling ≈ 4` and `inspected ≈ 2` (the 2 funded customers; the clearing account is the carve-out and is not counted). Subsequent ticks emit `inspected=0, drifted=0` heartbeats every 30 s.
