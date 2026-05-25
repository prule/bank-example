## Why

F09 (dev-data-seeding) just shipped. F06 transfers and F08 opens both write `journal_entry` rows in status `PENDING`, with paired `ledger_movement` rows that already balance by construction (F06 debits and credits the same amount inside one `@Transactional` boundary; F08 routes through F06). Today, those Pending rows never transition: nothing reads them back, nothing checks them, nothing promotes them to `VERIFIED`. From an audit perspective the ledger is half-built — every entry is forever "we promised this would balance" rather than "we re-checked and it does".

F10 is the next slot in the manifest's `[F10]` build group and a hard prerequisite for F11 (`balance-drift-detection`). It introduces the continuous background sweep that picks up Pending journals, re-verifies them at the database layer, and promotes each to `VERIFIED` or — if the aggregate sum is non-zero — to terminal `FAILED`. A failed journal cascades to Suspending every account it touches, so corrupted ledger state is contained the moment it's discovered. Every primitive F10 needs is already in place:

- `JournalEntries.findByStatus(PENDING, limit)` returns a bounded page of Pending entries ordered by `(timestamp ASC, id ASC)` — F02 / F05 shipped it explicitly for this consumer.
- `JournalEntries.isBalanced(JournalEntryId)` is a single aggregate JPA query: `SELECT COALESCE(SUM(CASE WHEN movement_type='CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE journal_entry_id = ?` — no in-memory iteration, no `findAll`.
- `JournalEntry.markVerified()` / `markFailed()` are the only state transitions the aggregate allows from `PENDING`; both are guarded by `IllegalJournalStatusTransitionException` so a double-mark is a loud error.
- `Account.suspend()` transitions ACTIVE → SUSPENDED and is idempotent for an already-SUSPENDED row (the F01 invariant only rejects CLOSED → SUSPENDED).
- `BankCoreApplication` already declares `@EnableScheduling` — wired in F00 for this sweep and for F11.

F10 is therefore a composition change, not a new primitive. It introduces the use case, the scheduler shell, the suspend-cascade helper, and the externalised configuration; everything else is consumed unchanged.

## What Changes

- Introduce the plain-Java `VerifyPendingJournals` use case in `application/src/main/java/com/bank/core/application/ledger/`. Constructor takes `JournalEntries journals`, `Accounts accounts`, and an `int pageSize`. Single public method `SweepReport sweep()` orchestrates one tick:
  1. Load `journals.findByStatus(VerificationStatus.PENDING, pageSize)`. Empty page → return `SweepReport.empty()`.
  2. For each loaded `JournalEntry entry` in iteration order: invoke a private `processOne(entry)` wrapped in `try { ... } catch (RuntimeException ex) { LOG bridge via the runner — see Decisions in design.md }`. The catch returns a per-entry "processing-failed" marker so the outer loop continues with the next entry per the spec's per-journal resilience requirement; the exception itself does NOT propagate out of `sweep()`.
  3. `processOne(entry)` resolves the verdict at the data layer: `boolean balanced = journals.isBalanced(entry.id())`. If balanced → `entry.markVerified()`; if not balanced → `entry.markFailed()` and then call `suspendTouchedAccounts(entry)`. In both cases call `journals.save(entry)` so the status-only update writes via `JournalEntriesJpaAdapter.save(...)`'s upsert-by-id path.
  4. `suspendTouchedAccounts(entry)` iterates `entry.movements()`, collects the distinct `AccountId`s (in encounter order), and for each loads the account via the new `accounts.findById(AccountId)` port method (see next bullet). If the account is already `SUSPENDED` or `CLOSED`, skip it; otherwise call `account.suspend()` and `accounts.save(account)`. The cascade SHALL ignore movements whose `accountId` resolves to no row (defensive: F02's foreign-key constraint makes this impossible in production, but loading via a freshly hand-constructed `JournalEntry` in tests must not NPE).
  5. Return `SweepReport(processed, verified, failed, errored)` summarising the tick.
- Add a new `Accounts.findById(AccountId)` method to the application-module port. The infrastructure adapter delegates to the existing `AccountRepository.findById(UUID)` (Spring Data already provides this on `JpaRepository`); no new repository method, no schema change. Update the `Accounts` Javadoc to name F10's suspend-cascade as the consumer. This is an additive change — every existing caller (`OpenAccount`, `TransferFunds`, `SeedData`) only uses `findByNumber` and is unaffected. The `account-lookup` capability spec is touched only to add a "MAY also be loaded by `AccountId`" note alongside the existing public account-number lookup; no behavioural requirement changes for the HTTP surface.
- Introduce `SweepReport` as an `application/.../ledger/SweepReport.java` record `(int processed, int verified, int failed, int errored)`. Compact constructor null-safe (all primitives); a static `SweepReport empty() { return new SweepReport(0, 0, 0, 0); }`. The fields are mutually exclusive per entry: `processed == verified + failed + errored` is a class-level invariant the use case maintains, asserted in unit tests.
- Introduce the scheduler shell `JournalVerificationScheduler` in `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/`. This is a `@Component` annotated `@Scheduled(fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}", initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")`. Constructor injects `VerifyPendingJournals useCase`. The single `void tick()` method invokes `useCase.sweep()` and then emits exactly one INFO log line: `"journal verification tick: processed={}, verified={}, failed={}, errored={}"`. The scheduler does NOT own a `@Transactional` — per-journal transactions are owned by the adapter calls (`JournalEntries.save` is already `@Transactional` on `JournalEntriesJpaAdapter`, as is `Accounts.save`). The scheduler also does NOT catch exceptions: Spring's `@Scheduled` infrastructure already isolates failures across ticks; an exception out of `tick()` is logged by Spring at WARN and does not stop subsequent ticks from firing. See design.md Decision 3 for the per-call-not-per-tick transactional rationale.
- Introduce externalised configuration `JournalVerificationProperties` (`@ConfigurationProperties("bank.journal-verification")`) in `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/`. Fields:
  - `long fixedDelayMs` (default `10_000` — the spec's 10-second target).
  - `long initialDelayMs` (default `5_000` — small head start so the sweep doesn't race the application context's first POST handler).
  - `int pageSize` (default `50` — the spec's target).
  - Compact constructor applies defaults when Spring binds null/zero (a `pageSize` ≤ 0 falls back to `50` with a single WARN log via a static helper; a non-positive `fixedDelayMs` falls back to `10_000`; `initialDelayMs` is allowed to be zero for tests).
- Wire the use case and properties in `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`:
  - Add `JournalVerificationProperties.class` to the existing `@EnableConfigurationProperties({TransferLockingProperties.class, SeedProperties.class, JournalVerificationProperties.class})` array.
  - Add `@Bean VerifyPendingJournals verifyPendingJournals(JournalEntries journals, Accounts accounts, JournalVerificationProperties props)` factory method constructing the plain-Java use case from `props.pageSize()`. Keeps `@Value` out of the application module.
  - The `@Component` annotation on `JournalVerificationScheduler` is enough for it to be picked up by component scan; no explicit `@Bean` method is needed.
- Add the property block to configuration:
  - `bootstrap/src/main/resources/application.yaml`: add `bank.journal-verification:` block under the existing top-level `bank:` key with defaults (`fixed-delay-ms: 10000`, `initial-delay-ms: 5000`, `page-size: 50`). The block is documented inline so operators inspecting the file see the sweep cadence at a glance.
  - `bootstrap/src/test/resources/application-test.yaml`: override with a much smaller `initial-delay-ms: 0` and `fixed-delay-ms: 500` so integration tests can observe a sweep within a few hundred ms rather than the production 10 s. The `page-size` is left at the default 50 in the test profile.
- No new database migration. F02 / F05's existing schema (`journal_entry.verification_status`, `ledger_movement` with `journal_entry_id` + `amount` + `movement_type` + `account_id`) is sufficient. The `isBalanced(id)` aggregate query already exists on `JournalEntryRepository.sumSignedAmount(...)`.
- No HTTP changes. No OpenAPI changes. No new Gradle dependency.
- Tests:
  - **Application unit test** for `SweepReport` (`SweepReportTest`) — `empty()` produces all-zero, field round-trip, the `processed == verified + failed + errored` invariant is asserted (but enforced by the use case, not the record itself — see design.md Decision 5).
  - **Application unit test** for `VerifyPendingJournals` (`VerifyPendingJournalsTest`, JUnit 5 + Mockito) with mocked `JournalEntries` and `Accounts`:
    - Empty page: `sweep()` returns `SweepReport(0,0,0,0)`; no calls to `journals.save(...)` or `accounts.findById(...)`.
    - Page of 3 all-balanced journals: each transitions PENDING → VERIFIED, `journals.save(entry)` called once per journal, `accounts.findById(...)` never called (no suspend cascade on success), returned report is `(3, 3, 0, 0)`.
    - Page with one unbalanced journal: that journal transitions to FAILED, every distinct AccountId from its movements is loaded via `accounts.findById(...)`, each non-suspended account has `suspend()` invoked and `accounts.save(account)` called, the SUSPENDED account in the same movement list is NOT re-saved (idempotency), already-CLOSED accounts skipped, returned report reflects the failed count.
    - Per-journal resilience: when `journals.isBalanced(id)` throws on the 2nd of 3 journals, the 3rd still processes; the failing journal contributes 1 to `errored`; subsequent journals' `markVerified` still calls `journals.save(...)`.
    - Page consumed in order: assert iteration order matches the order returned by the mocked `findByStatus(...)`.
    - Constructor: null-rejection for both ports; `pageSize` ≤ 0 throws `IllegalArgumentException` with the message `"pageSize must be positive (was: " + pageSize + ")"` so a bad config fails at bean creation, not at runtime.
  - **Application unit test** for the suspend-cascade helper (covered inside `VerifyPendingJournalsTest`):
    - Three movements: one ACTIVE account → suspended + saved; one already-SUSPENDED → not re-saved; one CLOSED → not modified, not saved.
    - Same account appears on multiple movements (e.g. an exotic malformed entry): the account is loaded and suspended at most once per failed journal.
    - A movement whose AccountId resolves to `Optional.empty()` is silently skipped (defensive).
  - **Infrastructure unit test** for `JournalVerificationProperties` — defaults applied when constructed with zero/negative/null fields; explicit values round-trip; `pageSize` invariant enforced via the compact constructor's fallback.
  - **Infrastructure unit test** for `JournalVerificationScheduler` — `tick()` calls `verifyPendingJournals.sweep()` exactly once per invocation and emits exactly one INFO line whose template matches the spec's summary format; an exception thrown by the use case propagates out of `tick()` (so Spring's scheduler logs it at WARN per Decision 3, no swallowing).
  - **Integration test** `bootstrap/src/test/java/com/bank/core/scheduling/JournalVerificationIntegrationTest` (`@SpringBootTest` with `properties = {"bank.journal-verification.initial-delay-ms=0", "bank.journal-verification.fixed-delay-ms=200"}`, `@ActiveProfiles("test")`):
    - Seed a balanced journal in PENDING via `JournalEntries.save(...)` plus two real `Account` rows (one to debit, one to credit). Use `Awaitility.await().atMost(5, SECONDS).until(...)` to assert the journal transitions to VERIFIED.
    - Seed an unbalanced journal in PENDING (manually constructed via a side-channel JDBC insert so it bypasses `JournalEntry.create`'s balance invariant — the production code path cannot create an unbalanced journal, so this test fabricates exactly the corrupted state F10 is designed to catch). Await transition to FAILED; assert both accounts are SUSPENDED.
    - Mixed scenario: one balanced + one unbalanced in the same page; assert the balanced one verifies and the unbalanced one fails + cascades, in the same tick.
    - Already-VERIFIED and already-FAILED journals from prior ticks are not re-processed (assert by stable row counts after subsequent ticks).
    - Tick summary log line: attach a Logback `ListAppender` to `JournalVerificationScheduler`'s logger before context start; assert at least one INFO line matches the documented template `journal verification tick: processed=...`.
  - **ArchUnit / boundary verification**: F00's rules continue to pass. `VerifyPendingJournals`, `SweepReport`, and the additive `Accounts.findById(AccountId)` are framework-free; `JournalVerificationScheduler` and `JournalVerificationProperties` reside under `com.bank.core.infrastructure.scheduling..` so the entity / web confinement rules pass unchanged. Add one targeted ArchUnit assertion: `JournalVerificationScheduler` resides under `com.bank.core.infrastructure.scheduling`, paralleling the F09 `SeedArchUnitTest` pattern.

## Capabilities

### New Capabilities

None. The `journal-verification` capability spec already exists in `openspec/specs/journal-verification/spec.md`; this change implements it.

### Modified Capabilities

- `journal-verification`: refine the existing five high-level requirements into implementation-precise scenarios — property names (`bank.journal-verification.fixed-delay-ms`, `bank.journal-verification.page-size`), the `JournalEntries.findByStatus(PENDING, pageSize)` consumption, the database-side aggregate query via `isBalanced(id)`, the suspend-cascade order, the per-journal resilience semantics (try/catch inside the use case, exception from `tick()` surfaced by Spring's scheduler), and the summary log template. No requirement-level behaviour is removed.
- `account-lookup`: additive only. The `Accounts` port gains a new `findById(AccountId)` method consumed by F10's suspend cascade. No HTTP surface change, no behavioural requirement change.

## Impact

- **Code**:
  - `application/src/main/java/com/bank/core/application/ledger/VerifyPendingJournals.java` (new — plain-Java use case).
  - `application/src/main/java/com/bank/core/application/ledger/SweepReport.java` (new — plain-Java record).
  - `application/src/main/java/com/bank/core/application/account/Accounts.java` (modified — add `Optional<Account> findById(AccountId)`).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/account/AccountsJpaAdapter.java` (modified — implement `findById(AccountId)` via the existing `repository.findById(UUID)`).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/JournalVerificationScheduler.java` (new — `@Component @Scheduled` shell).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/scheduling/JournalVerificationProperties.java` (new — `@ConfigurationProperties`).
  - `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` (modified — register properties + bean factory for the use case).
- **Configuration**:
  - `bootstrap/src/main/resources/application.yaml` (modified — add `bank.journal-verification:` block with production defaults).
  - `bootstrap/src/test/resources/application-test.yaml` (modified — override with fast cadence for tests).
- **Schema / migrations**: none. The existing `journal_entry` and `ledger_movement` tables suffice; the aggregate query is already wired.
- **OpenAPI**: none. F10 has no HTTP surface.
- **Build**: one new test-only Gradle dependency — `org.awaitility:awaitility` (BOM-managed by Spring Boot, version comes for free) — added to `bootstrap`'s `testImplementation` so the integration test can poll the sweep outcome without hand-rolled `Thread.sleep`.
- **Conventions**:
  - Reaffirms F00's "application is Spring-free": `VerifyPendingJournals`, `SweepReport`, and the additive `Accounts.findById(AccountId)` have no Spring/JPA/openapi imports.
  - Reaffirms F02's `transactional-in-application` precedent: F10 introduces no `@Transactional` annotation. Per-journal transactions are owned by the adapter methods (each `journals.save` and `accounts.save` is a separate `@Transactional` write on the adapter). See design.md Decision 3.
  - Reaffirms F00's "orchestration-shells-thin": `JournalVerificationScheduler.tick()` is one delegation + one log line; all decision logic lives in `VerifyPendingJournals`.
- **Open decisions**:
  - **Unchanged / consumed**: `transactional-in-application` (F10 honours it — per-call transactions, no scheduler-level boundary), `scheduler-config-externalised` (F10 closes the corresponding part of this decision — properties under `bank.journal-verification.*`; F11 will close the rest by following the same pattern), `reactivation-playbook` (F11/operator concern; F10 only Suspends, never reactivates).
  - **No new open decision opened by this change.**
- **Downstream**:
  - **F11** (`balance-drift-detection`) consumes F10's contract transitively: every successful F11 reconciliation assumes the relevant journals are `VERIFIED`. F11 will need to know whether to re-scan journals that are still `PENDING` at reconciliation time — that's an F11 design choice, not an F10 spec change.
  - **No HTTP downstream** — F10 is operator-/auditor-facing through logs, not customers.
- **Backwards compat**: zero. Pre-F10, the journals existed and never moved out of `PENDING`. Post-F10, balanced ones move to `VERIFIED` and unbalanced ones move to `FAILED` plus suspend the touched accounts. No existing caller relies on `PENDING` being terminal; `VerificationStatus.canTransitionTo(...)` already allowed both transitions and only forbade them once status left `PENDING`. The new `Accounts.findById(AccountId)` method is additive — every existing caller is unaffected.
- **Operational notes**:
  - In a clean dev environment seeded by F09, F10's first tick verifies 2 journals (the funded customer opens) and emits a single `journal verification tick: processed=2, verified=2, failed=0, errored=0` log line. Subsequent ticks process zero. So in normal operation the only visible signal is the periodic "processed=0" heartbeat (every 10 s), which is intentional — it doubles as a liveness signal for the verification subsystem.
  - A `FAILED` journal is a high-severity event. The log line names the journal id, and every account it touched is SUSPENDED. There is no F10-level auto-recovery: an operator must investigate the cause, fix the underlying data corruption (manual SQL is the only path, since the domain forbids un-FAILing a journal), and either reactivate the suspended accounts (F11's playbook will own this, eventually) or close them.
  - The page size of 50 plus a 10-second cadence implies a steady-state throughput ceiling of 5 journals/s. The production workload is far below this; if a backlog ever develops (e.g. after a long outage), the spec's "tick is bounded in size" requirement explicitly chooses bounded latency-per-tick over throughput catch-up. An operator can temporarily raise `bank.journal-verification.page-size` to drain a backlog faster without a deploy.
