## 1. Application module — Accounts port additive method

- [ ] 1.1 Modify `application/src/main/java/com/bank/core/application/account/Accounts.java`: add `Optional<Account> findById(AccountId id)` between the existing `findByNumber(...)` and `save(...)` declarations. Add an `import com.bank.core.domain.AccountId` line. Update the class-level Javadoc to add a bullet under "Downstream consumers" naming F10 (`com.bank.core.application.ledger.VerifyPendingJournals`) as the consumer for the suspend cascade.
- [ ] 1.2 Modify `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/account/AccountsJpaAdapter.java`: implement the new method as `@Override @Transactional(readOnly = true) public Optional<Account> findById(AccountId id) { return repository.findById(id.value()).map(AccountMapper::toDomain); }`. No new repository method (inherits from `JpaRepository`); no new SQL.
- [ ] 1.3 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/account/` still returns zero matches.

## 2. Application module — SweepReport

- [ ] 2.1 Create `application/src/main/java/com/bank/core/application/ledger/SweepReport.java` as `public record SweepReport(int processed, int verified, int failed, int errored) { public static SweepReport empty() { return new SweepReport(0, 0, 0, 0); } }`. No constructor checks beyond the implicit primitive-non-null. Class Javadoc names the producer (`VerifyPendingJournals.sweep()`) and the invariant the producer maintains (`processed == verified + failed + errored`); explicitly states the invariant is NOT enforced by the record (see design.md Decision 5).
- [ ] 2.2 Add `application/src/test/java/com/bank/core/application/ledger/SweepReportTest.java` — `empty()` returns `(0,0,0,0)`; explicit construction round-trips all four fields; the invariant is documented but not enforced (constructing `new SweepReport(5, 2, 2, 0)` does NOT throw — the use case is what maintains the invariant).

## 3. Application module — VerifyPendingJournals use case

- [ ] 3.1 Create `application/src/main/java/com/bank/core/application/ledger/VerifyPendingJournals.java`. Final class. Public constructor `VerifyPendingJournals(JournalEntries journals, Accounts accounts, int pageSize)`. All three params validated: `Objects.requireNonNull` for the two ports with named messages; `if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive (was: " + pageSize + ")")`. Stored as final fields.
- [ ] 3.2 Field: `private static final Logger LOG = LoggerFactory.getLogger(VerifyPendingJournals.class)`. Add `org.slf4j` imports (the application module already has `org.slf4j:slf4j-api` per its `build.gradle.kts`).
- [ ] 3.3 Public method `SweepReport sweep()`:
  1. `List<JournalEntry> page = journals.findByStatus(VerificationStatus.PENDING, pageSize)`.
  2. `int verified = 0, failed = 0, errored = 0`.
  3. For each `JournalEntry entry : page`:
     - `try { processOne(entry); if (entry.status() == VerificationStatus.VERIFIED) verified++; else failed++; } catch (RuntimeException ex) { LOG.warn("journal verification failed on id={} ({}): {}", entry.id(), ex.getClass().getSimpleName(), ex.getMessage()); errored++; }`.
  4. Return `new SweepReport(page.size(), verified, failed, errored)`.
- [ ] 3.4 Private method `void processOne(JournalEntry entry)`:
  1. `boolean balanced = journals.isBalanced(entry.id())`.
  2. If balanced: `entry.markVerified()`; `journals.save(entry)`; return.
  3. Else: `entry.markFailed()`; `journals.save(entry)`; `LOG.error("journal verification failed: id={} — unbalanced; suspending touched accounts", entry.id())`; `suspendTouchedAccounts(entry)`.
- [ ] 3.5 Private method `void suspendTouchedAccounts(JournalEntry entry)`:
  - `LinkedHashSet<AccountId> uniqueIds = new LinkedHashSet<>()` over `entry.movements()` to preserve encounter order and dedup.
  - For each id: `accounts.findById(id).ifPresent(account -> { if (account.status() == AccountStatus.ACTIVE) { account.suspend(); accounts.save(account); } })`.
  - SUSPENDED accounts: the `if (account.status() == AccountStatus.ACTIVE)` guard skips both the `suspend()` call and the `save()`; no exception, no save.
  - CLOSED accounts: same guard skips them; the domain would throw `IllegalStatusTransitionException` if we called `suspend()` on a CLOSED account, so the guard prevents that.
  - `Optional.empty()` from `findById`: the `ifPresent(...)` collapses to a no-op; this is the defensive test-fixture path described in design.md Decision 4.
- [ ] 3.6 Class-level Javadoc names: (a) the per-call (not per-tick) transactional model carried by adapter methods; (b) per-journal try/catch as the resilience boundary; (c) suspend-cascade idempotency (already-Suspended skipped, already-Closed skipped, dedup by `LinkedHashSet`); (d) the `accounts.findById(...).ifPresent(...)` defensive skip; (e) the `SweepReport.processed == verified + failed + errored` invariant maintained by this class.
- [ ] 3.7 Add `application/src/test/java/com/bank/core/application/ledger/VerifyPendingJournalsTest.java`. JUnit 5, Mockito. Mocked `JournalEntries journals`, `Accounts accounts`. Test fixtures: an `Account.open(AccountNumber.of("ACC-A"), Money.ZERO)` helper, an `unbalancedJournalEntry(AccountId... touched)` helper that constructs a `JournalEntry` via `JournalEntry.rehydrate(...)` with two movements that don't balance (e.g. DEBIT 10 + CREDIT 5), and a `balancedJournalEntry(...)` helper using `JournalEntry.create(...)`.
- [ ] 3.8 Test `constructor_rejectsNullPortsAndNonPositivePageSize`:
  - `new VerifyPendingJournals(null, accounts, 50)` throws NPE with `"journals cannot be null"`.
  - `new VerifyPendingJournals(journals, null, 50)` throws NPE with `"accounts cannot be null"`.
  - `new VerifyPendingJournals(journals, accounts, 0)` throws `IllegalArgumentException` with `"pageSize must be positive (was: 0)"`.
  - `new VerifyPendingJournals(journals, accounts, -1)` throws `IllegalArgumentException` with `"pageSize must be positive (was: -1)"`.
- [ ] 3.9 Test `emptyPage_returnsZeroReport_noSaves`:
  - Stub `journals.findByStatus(PENDING, 50)` returns `List.of()`.
  - Call `sweep()`.
  - Assert `SweepReport(0, 0, 0, 0)`; verify `journals.save(...)` and `accounts.findById(...)` never invoked.
- [ ] 3.10 Test `pageOfBalancedJournals_allVerified_noCascade`:
  - Three balanced `JournalEntry` mocks (use Mockito `mock(JournalEntry.class)` or real `JournalEntry.create(...)` instances).
  - Stub `journals.isBalanced(...)` returns `true` for each.
  - Call `sweep()`.
  - Verify `journals.save(entry)` called once per journal (status transitioned to VERIFIED before save).
  - Verify `accounts.findById(...)` and `accounts.save(...)` never invoked.
  - Assert `SweepReport(3, 3, 0, 0)`.
- [ ] 3.11 Test `unbalancedJournal_failsAndSuspendsTouchedActiveAccounts`:
  - One unbalanced `JournalEntry` touching two ACTIVE accounts via two movements.
  - Stub `journals.isBalanced(entry.id())` returns `false`.
  - Stub `accounts.findById(id)` for each touched id returns the ACTIVE account.
  - Call `sweep()`.
  - Verify `journals.save(entry)` called once with status `FAILED`.
  - Verify `accounts.save(...)` called twice (both touched accounts), each with status `SUSPENDED`.
  - Assert `SweepReport(1, 0, 1, 0)`.
- [ ] 3.12 Test `unbalancedJournal_alreadySuspendedAccount_isNotResaved`:
  - One unbalanced journal touching one ACTIVE and one already-SUSPENDED account.
  - Stub `accounts.findById(activeId)` returns ACTIVE aggregate; `accounts.findById(suspendedId)` returns a SUSPENDED aggregate.
  - Call `sweep()`.
  - Verify `accounts.save(...)` called exactly once with the previously-ACTIVE (now SUSPENDED) account; the already-SUSPENDED account is loaded but NOT saved.
- [ ] 3.13 Test `unbalancedJournal_closedAccount_isSkipped`:
  - One unbalanced journal touching a CLOSED account.
  - Stub `accounts.findById(closedId)` returns a CLOSED aggregate.
  - Call `sweep()`.
  - Verify the cascade does not throw; `accounts.save(...)` not called for the CLOSED account; the CLOSED account remains CLOSED.
- [ ] 3.14 Test `unbalancedJournal_duplicateAccountIdAcrossMovements_suspendsOnce`:
  - Construct an unbalanced journal whose `movements()` list contains three movements all referencing the same `AccountId` (use `JournalEntry.rehydrate(...)` to bypass `JournalEntry.create`'s balance + at-least-two-movements check; or include a real second account for the at-least-two requirement).
  - Stub `accounts.findById(thatId)` returns ACTIVE.
  - Call `sweep()`.
  - Verify `accounts.findById(thatId)` invoked exactly once; `accounts.save(...)` for that id invoked at most once.
- [ ] 3.15 Test `unbalancedJournal_findByIdReturnsEmpty_isSilentlySkipped`:
  - Construct an unbalanced journal with one movement whose `accountId` resolves to `Optional.empty()` from `accounts.findById(...)`.
  - Call `sweep()`.
  - Verify no `account.suspend()` call (impossible — there's no account); no exception thrown; the journal still transitions to FAILED and `SweepReport(1, 0, 1, 0)` is returned.
- [ ] 3.16 Test `oneBadJournal_doesNotStopTick`:
  - Three Pending journals `J1`, `J2`, `J3`. Stub `journals.isBalanced(J1.id())` returns `true`, `journals.isBalanced(J2.id())` throws `RuntimeException("simulated")`, `journals.isBalanced(J3.id())` returns `true`.
  - Call `sweep()`.
  - Verify `journals.save(J1)` called (J1 verified); `journals.save(J2)` never called (J2 errored before transition); `journals.save(J3)` called (J3 verified).
  - Assert `SweepReport(3, 2, 0, 1)`.
- [ ] 3.17 Test `sweepReportInvariant_holdsForEveryScenario`:
  - Parameterised test (or repeated assertions across the above scenarios) verifying `report.processed() == report.verified() + report.failed() + report.errored()` after every `sweep()` call.
- [ ] 3.18 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/java/com/bank/core/application/ledger/` returns zero matches (slf4j is allowed).

## 4. Infrastructure module — JournalVerificationProperties

- [ ] 4.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/JournalVerificationProperties.java` as `@ConfigurationProperties("bank.journal-verification") public record JournalVerificationProperties(long fixedDelayMs, long initialDelayMs, int pageSize) { ... }`. Compact constructor applies defaults: `if (fixedDelayMs <= 0) fixedDelayMs = 10_000` (with a static helper that logs ONE WARN line at first fallback); `if (initialDelayMs < 0) initialDelayMs = 5_000`; `if (pageSize <= 0) pageSize = 50` (also with a WARN).
- [ ] 4.2 Class-level Javadoc explains: defaults match the spec targets (10 s cadence, 50 per page); the WARN-on-fallback semantics; the record is bound from `bank.journal-verification.*` in `application*.yaml`.
- [ ] 4.3 Add `infrastructure/src/test/java/com/bank/core/infrastructure/scheduling/JournalVerificationPropertiesTest.java`:
  - Explicit positive values round-trip.
  - `fixedDelayMs = 0` falls back to `10_000`; `fixedDelayMs = -1` falls back to `10_000`.
  - `pageSize = 0` falls back to `50`; `pageSize = -10` falls back to `50`.
  - `initialDelayMs = 0` is accepted as-is (tests need this).
  - `initialDelayMs = -1` falls back to `5_000`.

## 5. Infrastructure module — JournalVerificationScheduler

- [ ] 5.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/JournalVerificationScheduler.java`. Annotations: `@Component`. Constructor injects `VerifyPendingJournals useCase` (null-checked via `Objects.requireNonNull`).
- [ ] 5.2 Field: `private static final Logger LOG = LoggerFactory.getLogger(JournalVerificationScheduler.class)`.
- [ ] 5.3 Public method `void tick()` annotated `@Scheduled(fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}", initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")`:
  - `SweepReport report = useCase.sweep();`
  - `LOG.info("journal verification tick: processed={}, verified={}, failed={}, errored={}", report.processed(), report.verified(), report.failed(), report.errored());`
  - No try/catch — exceptions propagate to Spring's `TaskScheduler` (see design.md Decision 3).
- [ ] 5.4 Class-level Javadoc covers: `fixedDelayString` (not `fixedRateString`) — prevents tick overlap; per-call transactions live on adapter methods, scheduler owns none; no exception handling — Spring's scheduler logs failures at WARN and re-fires; the property placeholder defaults to the spec's 10 s / 5 s targets.
- [ ] 5.5 Add `infrastructure/src/test/java/com/bank/core/infrastructure/scheduling/JournalVerificationSchedulerTest.java`:
  - `tick_callsUseCaseOnce_andLogsSummary`: mock `VerifyPendingJournals.sweep()` returns `SweepReport(2, 1, 1, 0)`; capture INFO log via Logback `ListAppender`; assert exactly one INFO line whose formatted message equals `journal verification tick: processed=2, verified=1, failed=1, errored=0`.
  - `tick_emptySweep_emitsHeartbeat`: mock returns `SweepReport.empty()`; assert one INFO line `journal verification tick: processed=0, verified=0, failed=0, errored=0`.
  - `tick_useCaseThrows_propagates`: mock throws `RuntimeException("DB down")`; assert `RuntimeException` propagates out of `tick()`; assert NO INFO summary line is emitted (the WARN line, if any, is Spring's responsibility).
  - `scheduledAnnotation_usesFixedDelayPlaceholder`: reflect on `tick()`, read the `@Scheduled` annotation, assert `fixedDelayString()` equals literally `"${bank.journal-verification.fixed-delay-ms:10000}"` and `initialDelayString()` equals `"${bank.journal-verification.initial-delay-ms:5000}"`; assert `fixedRateString()` is empty / default (guards against a refactor to `fixedRate`).
- [ ] 5.6 Add an `Optional<JournalVerificationProperties>` constructor injection only if needed for tests; otherwise the property values are read by Spring's annotation placeholder resolution and the scheduler doesn't need the DTO directly. Stick with the simpler "no `JournalVerificationProperties` field on the scheduler" pattern — `JournalVerificationProperties` is consumed by the `@Bean` factory in `BankCoreApplication` (to pass `pageSize` into the use case constructor) and by Spring's placeholder resolution (for the `@Scheduled` delay strings).

## 6. Bootstrap module — wiring

- [ ] 6.1 Modify `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`:
  - Add `JournalVerificationProperties.class` to the existing `@EnableConfigurationProperties({TransferLockingProperties.class, SeedProperties.class, JournalVerificationProperties.class})` array.
  - Add `@Bean VerifyPendingJournals verifyPendingJournals(JournalEntries journals, Accounts accounts, JournalVerificationProperties props) { return new VerifyPendingJournals(journals, accounts, props.pageSize()); }` factory method. Javadoc: cites design.md Decision 2 (per-call transactions) and Decision 6 (no application-module access to `@Value`).
- [ ] 6.2 Imports added: `com.bank.core.application.ledger.VerifyPendingJournals`, `com.bank.core.infrastructure.scheduling.JournalVerificationProperties`. The `JournalEntries` import already exists for `transferFunds(...)`.

## 7. Configuration files

- [ ] 7.1 Modify `bootstrap/src/main/resources/application.yaml`. Under the existing top-level `bank:` key, add `journal-verification:` block:
  ```yaml
  journal-verification:
    # Background sweep that promotes Pending journal entries to Verified or Failed.
    # See openspec/specs/journal-verification/spec.md.
    fixed-delay-ms: 10000  # Spec target: every 10 s. fixedDelay (not fixedRate) so a slow tick never overlaps with the next.
    initial-delay-ms: 5000  # Small head start so the sweep doesn't race the application context's first POST handler.
    page-size: 50  # Spec target: at most 50 Pending journals per tick.
  ```
- [ ] 7.2 Modify `bootstrap/src/test/resources/application-test.yaml`. Add `journal-verification:` block with `fixed-delay-ms: 200`, `initial-delay-ms: 0`, `page-size: 50` so integration tests observe a sweep within a few hundred ms. Add a YAML comment explaining the override is test-only.

## 8. Integration test

- [ ] 8.1 Add `bootstrap/src/test/java/com/bank/core/scheduling/JournalVerificationIntegrationTest.java` annotated `@SpringBootTest` with `@ActiveProfiles("test")` and explicit `properties = {"bank.journal-verification.initial-delay-ms=0", "bank.journal-verification.fixed-delay-ms=200"}` (mirrors the test profile but pins the values in the annotation so a future profile edit can't silently break the test).
- [ ] 8.2 Use Awaitility (`org.awaitility:awaitility`, already provided by Spring Boot's test BOM — confirm with `./gradlew :bootstrap:dependencies | grep awaitility`; if absent, add it to `bootstrap/build.gradle.kts` under `testImplementation`).
- [ ] 8.3 Inject `Accounts`, `JournalEntries`, `JdbcTemplate`, `PlatformTransactionManager`, and `JournalVerificationProperties`. Use a `TransactionTemplate` to seed test data.
- [ ] 8.4 `@BeforeEach` wipes `ledger_movement`, `journal_entry`, `account` (in that order) and waits for any in-flight tick to complete by toggling `bank.seed.enabled=false` (already false in the test profile) and asserting an empty initial state.
- [ ] 8.5 Test `balancedJournal_transitionsToVerifiedWithinOneTick`:
  - Seed two accounts `A` and `B`, both ACTIVE at balance `100.00`.
  - Construct a balanced `JournalEntry` via `JournalEntry.create("test", clock.instant(), List.of(new Movement(a.id(), Money.of("25.00"), DEBIT), new Movement(b.id(), Money.of("25.00"), CREDIT)))`.
  - Persist via `journals.save(entry)`.
  - `Awaitility.await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).until(() -> journals.findById(entry.id()).orElseThrow().status() == VerificationStatus.VERIFIED)`.
  - Assert both accounts are still ACTIVE (verified path does not touch accounts).
- [ ] 8.6 Test `unbalancedJournal_transitionsToFailedAndSuspendsTouchedAccounts`:
  - Seed two accounts `A` and `B`, both ACTIVE at balance `100.00`.
  - Insert an unbalanced journal via side-channel `jdbcTemplate`: one row in `journal_entry` with status `PENDING`, two rows in `ledger_movement` whose amounts do not balance (e.g. DEBIT 10 against `A`, CREDIT 5 against `B`). Production code cannot create this state; the test fabricates exactly the corruption F10 is designed to catch.
  - Await the journal's `verification_status` column transitions to `FAILED`.
  - Assert both accounts' status transitions to `SUSPENDED` via `accounts.findByNumber(...)`.
  - Assert exactly one ERROR log line names the journal id (use a `ListAppender` attached to `VerifyPendingJournals`'s logger before context start, via an `ApplicationListener<ContextRefreshedEvent>` registered through `SpringApplicationBuilder.listeners(...)` — or simpler, attach in `@BeforeEach` after Logback's reset, since `@SpringBootTest` reuses the context).
- [ ] 8.7 Test `mixedPage_oneBalancedOneUnbalanced_eachProcessedInSameTick`:
  - Seed both kinds of journals as above.
  - Await both terminal states.
  - Assert no journal remains in `PENDING` after 5 seconds.
- [ ] 8.8 Test `verifiedAndFailedJournals_areNotRetriedOnLaterTicks`:
  - After the prior tests have committed terminal states, capture row counts for `account`, `journal_entry`, `ledger_movement` and the affected accounts' statuses.
  - Wait `3 * fixed-delay-ms` (≈600 ms with the test cadence).
  - Re-capture; assert all counts and statuses are unchanged.
- [ ] 8.9 Test `tickSummaryLogIsEmittedEachTick`:
  - Attach a `ListAppender` to the scheduler's logger.
  - Wait for at least 3 ticks (≈600 ms).
  - Assert the appender captured at least 3 INFO lines whose messages match `journal verification tick: processed=\d+, verified=\d+, failed=\d+, errored=\d+`.

## 9. ArchUnit / boundary verification

- [ ] 9.1 Confirm F00's `applicationHasNoFrameworkDependencies` still passes (no Spring/JPA imports in the new application/seed and application/ledger sources).
- [ ] 9.2 Add `bootstrap/src/test/java/com/bank/core/scheduling/JournalVerificationArchUnitTest.java`:
  - Assert `JournalVerificationScheduler` resides under `com.bank.core.infrastructure.scheduling..`.
  - Assert `JournalVerificationProperties` resides under `com.bank.core.infrastructure.scheduling..`.
  - Assert `VerifyPendingJournals` and `SweepReport` reside under `com.bank.core.application.ledger..`.

## 10. End-of-change verification

- [ ] 10.1 Run `./gradlew clean build`. All new tests pass; F00 ArchUnit suite still passes; no new production Gradle dependencies (awaitility is test-only, may already be transitively available via spring-boot-starter-test — confirm).
- [ ] 10.2 Run `openspec change validate journal-verification --strict`. Confirm a clean `Change "journal-verification" is valid` line.
- [ ] 10.3 Run `openspec validate --specs journal-verification --specs account-lookup`. Confirm both modified specs validate against the OpenSpec schema after the delta is merged.
- [ ] 10.4 Manual smoke: `./gradlew :bootstrap:bootRun --args='--spring.profiles.active=dev'`. Within 15 seconds (the default 5 s initial delay + a 10 s tick), captured stdout shows exactly one `journal verification tick: processed=2, verified=2, failed=0, errored=0` line — the 2 funded customer opens from F09's seed are verified. Subsequent ticks emit `processed=0` heartbeats every 10 s.
