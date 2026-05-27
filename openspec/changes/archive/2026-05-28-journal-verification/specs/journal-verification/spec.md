## MODIFIED Requirements

### Requirement: Periodic, bounded sweep

A scheduled component `JournalVerificationScheduler` SHALL run a single `tick()` method continuously on a fixed delay between runs, with the delay sourced from the property `bank.journal-verification.fixed-delay-ms` (default `10000`, target: every 10 seconds) and the first run delayed by `bank.journal-verification.initial-delay-ms` (default `5000`). The annotation SHALL be `@Scheduled(fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}", initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")` — `fixedDelayString`, not `fixedRateString`, so a slow tick never overlaps with the next one. Each tick SHALL delegate to `VerifyPendingJournals.sweep()` which calls `JournalEntries.findByStatus(VerificationStatus.PENDING, pageSize)` to load at most `bank.journal-verification.page-size` (default `50`, target: 50) Pending journals; `VERIFIED` and `FAILED` journals SHALL NOT be loaded because the filter excludes them by status.

#### Scenario: Pending journal moves to Verified within one tick
- **WHEN** a balanced `JournalEntry` is persisted via `JournalEntries.save(...)` with status `PENDING` and the scheduler is configured with `bank.journal-verification.fixed-delay-ms=200`, `initial-delay-ms=0`
- **THEN** within 5 seconds (an `Awaitility.await().atMost(5, SECONDS)` poll) the journal's persisted `verification_status` column transitions to `VERIFIED`; the same row's other columns (`description`, `entry_timestamp`, the joined `ledger_movement` rows) are unchanged

#### Scenario: Tick is bounded by the configured page size
- **WHEN** the database holds 200 Pending journals and `bank.journal-verification.page-size=50`
- **THEN** a single invocation of `JournalVerificationScheduler.tick()` causes `JournalEntries.findByStatus(VerificationStatus.PENDING, 50)` to be called exactly once and at most 50 journals are processed in that tick (the next tick processes the next page)

#### Scenario: Verified and Failed journals are not re-loaded
- **WHEN** the database holds 3 Pending journals, 7 Verified journals, and 2 Failed journals, and a tick runs
- **THEN** `JournalEntries.findByStatus(VerificationStatus.PENDING, ...)` returns exactly the 3 Pending entries; no Verified or Failed entry is loaded, mutated, or saved during the tick

#### Scenario: fixedDelay (not fixedRate) prevents tick overlap
- **WHEN** the production source of `JournalVerificationScheduler.tick()` is inspected and its `@Scheduled` annotation is read
- **THEN** the annotation declares `fixedDelayString` (not `fixedRateString`), guaranteeing the next tick fires `fixedDelayMs` after the previous tick *completes* rather than after it *starts*; a 12-second tick followed by a 10-second delay is correct behaviour

#### Scenario: Property name typo is caught by a reflection test
- **WHEN** the production source of `JournalVerificationScheduler.tick()` is inspected via reflection and the `@Scheduled` annotation's `fixedDelayString()` is read
- **THEN** the placeholder string equals exactly `"${bank.journal-verification.fixed-delay-ms:10000}"` so a refactor that misspells the property name fails the reflection assertion rather than silently disabling the sweep

### Requirement: Database-side balance check

The balance check SHALL be performed by `JournalEntries.isBalanced(JournalEntryId)`, which is implemented by `JournalEntriesJpaAdapter.isBalanced(...)` as a single aggregate JPA query — `SELECT COALESCE(SUM(CASE WHEN m.movementType = CREDIT THEN m.amount ELSE -m.amount END), 0) FROM LedgerMovementEntity m WHERE m.journalEntryId = :journalId` returning `BigDecimal` — and reports balanced iff the sum's signum is zero. The sweep SHALL invoke `isBalanced(id)` exactly once per Pending journal per tick. The sweep SHALL NOT call `JournalEntries.findById(id)` solely to load movements and balance them in Java; the per-entry domain aggregate already loaded by `findByStatus(...)` is used only for `markVerified()/markFailed()` and for iterating `movements()` during the suspend cascade.

#### Scenario: Check runs as one aggregate SQL per journal
- **WHEN** the sweep processes a single Pending journal `J`
- **THEN** `JournalEntries.isBalanced(J.id())` is invoked exactly once during the tick (verifiable by `Mockito.verify(...).times(1)` in unit tests, or by enabling SQL logging at TRACE in integration tests and counting `SUM(CASE WHEN movement_type` occurrences); no `repository.findAll()` is called; the application does not iterate `J.movements()` to sum them in Java for the verdict (iteration of `J.movements()` happens only inside the suspend cascade on failure)

#### Scenario: isBalanced returns true iff sum signum is zero
- **WHEN** the `JournalEntriesJpaAdapter.isBalanced(id)` source is inspected
- **THEN** it returns `sum != null && sum.signum() == 0`; a non-null non-zero sum returns `false`; a missing journal id returns `false` via the `existsById(id.value())` precondition

### Requirement: Unbalanced journal fails and suspends every referenced account

If `JournalEntries.isBalanced(entry.id())` returns `false`, the use case SHALL call `entry.markFailed()`, persist via `JournalEntries.save(entry)`, then invoke the suspend cascade. The cascade SHALL walk `entry.movements()`, deduplicate the `AccountId`s in encounter order (a `LinkedHashSet<AccountId>`), and for each unique id load the account via `Accounts.findById(AccountId)`. If the loaded account's `status()` is `ACTIVE`, the cascade SHALL call `account.suspend()` and `accounts.save(account)`. If the status is `SUSPENDED` or `CLOSED` the cascade SHALL skip the account (no re-save, no exception). If `accounts.findById(...)` returns `Optional.empty()` the cascade SHALL silently skip that movement (defensive — production foreign-key constraints make this unreachable). A high-severity (ERROR-level) log line SHALL name the journal id when the failure is detected. `FAILED` SHALL be terminal — `VerificationStatus.canTransitionTo(...)` already forbids any transition out of `FAILED`, so subsequent ticks' `findByStatus(PENDING, ...)` filter naturally excludes Failed rows.

#### Scenario: Unbalanced journal moves to Failed and suspends all touched accounts
- **WHEN** an unbalanced `JournalEntry` is inserted via side-channel JDBC (production code cannot create one — `JournalEntry.create(...)` rejects it; the test fabricates exactly the corrupted state this capability is designed to catch) with two `ledger_movement` rows, one DEBIT against account `A` (status `ACTIVE`) and one CREDIT against account `B` (status `ACTIVE`), and the amounts do not sum to zero
- **THEN** within 5 seconds the journal's `verification_status` transitions to `FAILED`; account `A`'s status transitions to `SUSPENDED`; account `B`'s status transitions to `SUSPENDED`; one ERROR-level log line names the journal id; the journal's own movements and timestamps are unchanged

#### Scenario: Suspend cascade is idempotent across already-Suspended accounts
- **WHEN** an unbalanced journal touches two accounts where one is already `SUSPENDED` and one is `ACTIVE`
- **THEN** the already-Suspended account is loaded once via `accounts.findById(...)` and then skipped (no `accounts.save(...)` call for it, no `IllegalStatusTransitionException`); the ACTIVE account is suspended and saved exactly once

#### Scenario: Suspend cascade skips already-Closed accounts
- **WHEN** an unbalanced journal touches an account whose status is `CLOSED`
- **THEN** the cascade skips the CLOSED account (the domain forbids CLOSED → SUSPENDED via `IllegalStatusTransitionException`); no `account.suspend()` call, no `accounts.save(...)` call; the cascade does not raise an exception

#### Scenario: Duplicate AccountId across movements is suspended at most once
- **WHEN** an unbalanced journal has three movements all referencing the same `AccountId` (an exotic malformed entry; production never produces this)
- **THEN** the cascade calls `accounts.findById(thatId)` exactly once, `account.suspend()` at most once, and `accounts.save(account)` at most once — the `LinkedHashSet<AccountId>` deduplication prevents a second `suspend()` attempt that would throw `IllegalStatusTransitionException` against the now-SUSPENDED aggregate

#### Scenario: Failed journal is not retried on later ticks
- **WHEN** a sweep tick runs after a journal has transitioned to `FAILED`
- **THEN** the Failed journal is excluded from `JournalEntries.findByStatus(VerificationStatus.PENDING, ...)`; the use case never sees it; no further `account.suspend()` is attempted for it

#### Scenario: Failed-journal log line names the journal id
- **WHEN** the cascade transitions a journal to `FAILED`
- **THEN** the captured ERROR log contains a line whose formatted message includes the journal id's UUID string in canonical lower-case form, suitable for an operator to grep against `journal_entry.id`

### Requirement: Per-journal resilience

`VerifyPendingJournals.sweep()` SHALL wrap each per-journal processing block in a `try { ... } catch (RuntimeException ex) { ... }` so that a single journal whose balance check, status transition, or suspend cascade throws does NOT stop the remaining journals in the same page. The caught exception SHALL be logged at WARN level naming the failing journal id and the exception class, then the loop continues to the next journal; the failing journal's contribution to the tick is `errored += 1` rather than `verified` or `failed`. The use case's `sweep()` method SHALL NOT itself propagate the per-journal exception. An exception that escapes the per-journal try/catch (e.g. raised by `findByStatus(...)` before any journal is processed, or by the outer iteration) SHALL propagate out of `sweep()` and consequently out of `JournalVerificationScheduler.tick()`; Spring's `@Scheduled` infrastructure then logs the failure at WARN and re-fires the next tick at the configured delay.

#### Scenario: One bad journal does not stop the tick
- **WHEN** a tick loads three Pending journals `J1`, `J2`, `J3` from `findByStatus(...)`, and `JournalEntries.isBalanced(J2.id())` throws `RuntimeException("simulated DB error")`
- **THEN** `J1` is processed and (assuming balanced) transitions to `VERIFIED`; `J3` is processed and (assuming balanced) transitions to `VERIFIED`; `J2` remains in `PENDING`; the returned `SweepReport` is `(processed=3, verified=2, failed=0, errored=1)`; the captured WARN log contains one line naming `J2.id()` and `RuntimeException`

#### Scenario: Scheduler does not swallow exceptions from sweep
- **WHEN** the use case's outer `findByStatus(...)` call throws (e.g. database is down)
- **THEN** `JournalVerificationScheduler.tick()` does NOT catch the exception; it propagates to Spring's `TaskScheduler` which logs it at WARN; the next scheduled tick still fires at the configured delay (verified by an integration test that injects a DataSource failure once, then succeeds on the next tick)

### Requirement: Tick emits a summary log line

After `VerifyPendingJournals.sweep()` returns, `JournalVerificationScheduler.tick()` SHALL emit exactly one INFO log line whose message template is `"journal verification tick: processed={}, verified={}, failed={}, errored={}"` with the four counts from the returned `SweepReport`. The line SHALL be emitted on every tick including empty ones (where processed/verified/failed/errored are all zero), so absence of the line is itself a signal that the scheduler stopped. A tick that aborts mid-page because of an exception escaping `sweep()` SHALL NOT emit the summary line (the WARN line from Spring's scheduler is the signal in that case).

#### Scenario: Per-tick summary line is emitted with the counts
- **WHEN** a tick processes a page containing one balanced and one unbalanced journal (no per-journal exceptions)
- **THEN** the captured INFO log contains exactly one line whose formatted message equals `journal verification tick: processed=2, verified=1, failed=1, errored=0`

#### Scenario: Empty tick still emits the heartbeat
- **WHEN** a tick finds zero Pending journals
- **THEN** the captured INFO log contains exactly one line `journal verification tick: processed=0, verified=0, failed=0, errored=0` — the empty tick is the operator's liveness signal that the sweep is still running

#### Scenario: SweepReport invariant holds
- **WHEN** any tick completes successfully and produces a `SweepReport`
- **THEN** `report.processed() == report.verified() + report.failed() + report.errored()` (asserted by a unit test in `VerifyPendingJournalsTest` for every test scenario; the use case is the sole constructor of `SweepReport` and maintains the invariant by incrementing exactly one of the three counters per processed journal)
