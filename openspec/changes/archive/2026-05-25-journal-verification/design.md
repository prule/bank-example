## Context

The `journal-verification` capability spec already exists at `openspec/specs/journal-verification/spec.md` with five high-level requirements (periodic-bounded-sweep, DB-side balance check, fail-and-suspend, per-journal resilience, summary log). Everything F10 needs is already on the classpath:

- F02 (`immutable-ledger`) shipped `JournalEntry` with `PENDING/VERIFIED/FAILED` status and `markVerified()/markFailed()` transitions.
- F02 / F05 shipped the `JournalEntries` port with `findByStatus(VerificationStatus, int)` and `isBalanced(JournalEntryId)`.
- The `JournalEntriesJpaAdapter.isBalanced(...)` implementation is already a single aggregate query (`SELECT SUM(CASE WHEN movement_type='CREDIT' THEN amount ELSE -amount END)`).
- `JournalEntry.movements()` exposes the immutable list F10 walks to drive the suspend cascade.
- `Account.suspend()` enforces F01's status transitions (rejects CLOSED → SUSPENDED, idempotent for already-SUSPENDED).
- `BankCoreApplication` carries `@EnableScheduling` (F00 wiring).
- F09 (`dev-data-seeding`) commits 2 funded customer opens in dev, giving F10 real Pending journals to verify out of the gate.

Constraints inherited from earlier changes:

- **Application module is Spring-free** (F00 `applicationHasNoFrameworkDependencies`). No `@Component`, `@Scheduled`, `@Transactional`, `@Value` in `application/src/main/java`.
- **Domain module is JDK-only**. F10 adds no domain types.
- **Transactional boundaries live in adapters or controllers** (F02 `transactional-in-application`). F10 must NOT introduce a use-case-level or scheduler-level `@Transactional`.
- **One use case, one orchestration shell** (F00 `orchestration-shells-thin`). The scheduler is one delegation + one log line.
- **`@EnableScheduling` is already on** — F10 just uses `@Scheduled`.

Stakeholders:

- The operator watching audit dashboards who needs every Pending journal to either turn green (Verified) within ten seconds or go red (Failed) loudly enough to wake them.
- F11 (`balance-drift-detection`), which assumes journals it reconciles against are Verified — F10's promotion latency is F11's eventual-consistency budget.
- The developer running the dev profile who will see exactly one heartbeat line every 10 s — anything else in the log is a real signal.

## Goals / Non-Goals

**Goals:**

- A continuous, bounded-page background sweep that drives every Pending journal to a terminal status (Verified or Failed) within one tick.
- Database-side balance check via the existing single-aggregate-query port method — no `findAll`, no in-memory iteration.
- Loud, contained failure: an unbalanced journal becomes terminal `FAILED` and every account it touched (regardless of which side of the entry) is Suspended.
- Per-journal resilience: one bad journal does not stop the rest of the page.
- One summary log line per tick (`processed=N, verified=V, failed=F, errored=E`) so an audit dashboard can chart sweep health.
- Externalised cadence + page size (`bank.journal-verification.*`) so production can tune without a deploy.
- Implementation-precise spec scenarios (property names, log template, transactional boundary, cascade idempotency) so the test suite can assert against them directly.

**Non-Goals:**

- Auto-recovery of `FAILED` journals or auto-reactivation of suspended accounts. The spec is explicit: `FAILED` is terminal. Reactivation is F11's playbook concern.
- Re-running the balance check against an already-Verified journal "just in case". The spec forbids retouching `VERIFIED` and `FAILED`; the `findByStatus(PENDING, ...)` filter ensures it.
- Cross-tick state. Each tick is independent — no cursor, no persistent "last seen id", no resumable pagination. The `findByStatus(PENDING, pageSize)` query naturally drains the backlog over consecutive ticks because Verified rows fall out of the filter.
- A wall-clock SLA tighter than 10 s. The spec says "target ≤ 10 s" and that's the production default.
- An HTTP endpoint for "verify this journal now". Operator-triggered verification is out of scope; the sweep is the only entry point.

## Decisions

### Decision 1: Spring `@Scheduled(fixedDelay)`, not a custom thread / `@Async`

`JournalVerificationScheduler.tick()` is annotated `@Scheduled(fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}", initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")`. `fixedDelay` (not `fixedRate`) means the next tick fires `fixedDelay` ms after the previous tick *completes* — so a tick that takes 8 s to process a full page is followed by a 10 s wait, never overlapping with itself.

**Why:**

- `@EnableScheduling` is already wired in `BankCoreApplication`. Spring's `TaskScheduler` provides the thread pool, the per-tick exception isolation, and the rejection policy for free.
- `fixedDelay` avoids the `fixedRate` failure mode where a slow tick causes back-to-back firings until the runtime catches up — under sustained load, that mode would amplify backlog rather than drain it.
- A custom `ExecutorService` + `ScheduledFuture` would have to re-implement those properties and would not surface in Spring Boot Actuator's `/actuator/scheduledtasks` endpoint, which is the operator's first stop when investigating "is the sweep running?".

**Alternatives considered:**

- `fixedRate` — rejected per the slow-tick amplification above.
- `@Async` on a producer-consumer queue — overkill for a 5/s steady-state throughput; the scheduled bean is simpler and observable.
- A dedicated `Quartz` job — the bank-core service is a single instance, so the heavyweight Quartz infrastructure is unjustified. If the service is ever clustered, F10's idempotency (driven by `PENDING` status, not a "claimed-by-me" lock) means two schedulers competing for the same journal will harmlessly race; only one will win the optimistic-update on `verification_status`, the other will see the row already moved and skip — but this would require a follow-up `markVerified()`-protected-by-version-column change. Documented as a deferred concern; F10 ships single-instance.

### Decision 2: Per-call transactions in the adapter, not per-tick or per-journal

F10 does NOT wrap the sweep loop in a `@Transactional`. Each interaction with the database is a separate, adapter-owned transaction:

- `journals.findByStatus(PENDING, pageSize)` — `JournalEntriesJpaAdapter` already declares `@Transactional(readOnly = true)` on this method.
- `journals.isBalanced(id)` — `@Transactional(readOnly = true)` on the adapter; one aggregate SELECT per journal.
- `journals.save(entry)` — `@Transactional` on the adapter; the status-only update committed independently.
- `accounts.findById(id)` — `@Transactional(readOnly = true)`.
- `accounts.save(account)` — `@Transactional`.

**Why per-call, not one big transaction per tick:**

- A page of 50 journals processed under a single transaction would hold a long-running connection from Hikari's pool for the duration of the tick. Under contention (concurrent F06 transfers wanting their own short transactions), this would starve the pool.
- One big transaction would also mean that an exception on journal #37 would roll back the verifications of journals #1–36 even though they were genuinely balanced. The spec's per-journal resilience requirement demands the opposite.
- The spec explicitly does NOT require atomicity across "mark failed" + "suspend touched accounts". If `markFailed()` commits and then `accounts.save(...)` for one of the touched accounts throws, the journal is FAILED-on-disk and one account is unsuspended — which is exactly the loud, observable state F11 will later flag (the journal is FAILED but a touched account is ACTIVE). The next operator action is the same either way: investigate the FAILED journal. Coupling the two writes into one transaction would just turn this into a silent rollback that nobody sees.

**Why not per-journal as a single explicit transaction:**

- We *could* introduce a thin infrastructure facade `VerifyOneJournalService` annotated `@Transactional` wrapping the "isBalanced → mark → save → cascade" sequence. We chose not to because:
  1. F02's existing `JournalEntries.save(...)` is already `@Transactional` on the adapter — the spec-relevant "the status moved" guarantee is already atomic per journal.
  2. The per-journal `@Transactional` facade would NOT include the suspend cascade either way (each `accounts.save(...)` runs its own transaction), so the facade would only add ceremony, not atomicity.
  3. The spec scenario "One bad journal does not stop the tick" is honoured by the use-case-level try/catch around the per-journal processing — that's where the per-journal boundary lives, semantically.

**Alternatives considered:**

- `@Transactional(propagation = REQUIRES_NEW)` on a per-journal facade. Rejected — adds two infrastructure classes (the facade plus an interface adapter to keep the application module Spring-free) for no behavioural gain over the existing per-adapter transactions.
- `TransactionTemplate` instantiated in the scheduler and passed to the use case. Rejected — leaks `org.springframework.transaction.support` into the application module.

### Decision 3: Scheduler does NOT catch exceptions; per-journal try/catch lives in the use case

`JournalVerificationScheduler.tick()` calls `useCase.sweep()` then logs the summary. There is no surrounding `try/catch`. The use case's `sweep()` method is what wraps each per-journal processing block in `try { ... } catch (RuntimeException ex) { ... }`, increments the `errored` counter, and continues to the next journal.

**Why this split:**

- Spring's `@Scheduled` infrastructure (specifically `org.springframework.scheduling.support.ScheduledMethodRunnable`) already logs uncaught exceptions at WARN level and continues firing subsequent ticks at the configured delay. So an exception that escapes the *use case* (e.g. a `DataSource` outage that breaks `findByStatus(...)` before the first journal is even loaded) gets the visibility it deserves without F10 reinventing it.
- The per-journal try/catch lives where the per-journal knowledge lives: inside `processOne(entry)` in the use case. The scheduler doesn't need to know what "per-journal" means.
- This is the same split as F09: `SeedDataRunner` lets `seedData.seed()`'s exceptions propagate; the use case decides what's recoverable.

**Trade-off accepted:** if the use case's *outer* loop (e.g. the iteration over the page) throws an unexpected `RuntimeException` for reasons unrelated to a single journal (e.g. an OOME, or a thread interrupt), the tick aborts mid-page. The next tick re-loads the still-Pending rows and continues. We accept this because the alternative — a scheduler-level try-catch — would hide that something fundamental broke.

### Decision 4: Suspend cascade visits each touched account at most once and tolerates already-Suspended

For an unbalanced journal, the cascade walks `entry.movements()`, deduplicates by `AccountId` (preserving encounter order), and for each: loads the account, skips if status is `SUSPENDED` or `CLOSED`, otherwise calls `account.suspend()` and `accounts.save(account)`.

**Why dedup is needed:**

- F02's `Movement` invariants forbid zero amounts but do NOT forbid two movements with the same `accountId` on the same side (or even the same side and same amount). In production this won't happen — F06 produces exactly one DEBIT and one CREDIT against two distinct accounts — but an unbalanced journal is, by definition, a corruption; we don't get to assume the malformed entry obeys the well-formed invariants. Dedup is cheap (a `LinkedHashSet<AccountId>`) and prevents an `IllegalStatusTransitionException` on the second attempt to suspend the same account.

**Why already-Suspended is silently skipped:**

- `Account.suspend()` is idempotent for an already-SUSPENDED row at the *domain* level (the F01 invariant only rejects CLOSED → SUSPENDED), so calling it again is *correct*. But it would force a redundant `accounts.save(...)` (a JPA UPDATE writing the same status). Skipping the save is a cheap optimisation and means the spec's per-tick log line counts of "verified/failed" stay meaningful — they reflect *changes*, not no-ops.

**Why already-CLOSED is silently skipped:**

- `Account.suspend()` would throw `IllegalStatusTransitionException` for a CLOSED account. We treat that as the operator's prior decision: a CLOSED account is already out of business, no further action makes sense. The spec says "every account ... SHALL be Suspended (per [[account-domain]])" — that "per account-domain" qualifier is what permits us to skip the operation the domain forbids.

**Defensive note:** if a movement's `accountId` resolves to `Optional.empty()` from `accounts.findById(...)` (impossible in production thanks to the F02 foreign-key constraint, but possible in unit tests that hand-craft a `JournalEntry`), the cascade silently skips that movement rather than NPE'ing. The use case's class-level Javadoc names this as a test-fixture concession, not a production behaviour.

### Decision 5: `SweepReport` enforces its invariant via the use case, not the record

`SweepReport(int processed, int verified, int failed, int errored)` does NOT enforce `processed == verified + failed + errored` in its compact constructor. The invariant is maintained by the use case's accumulation loop and asserted in the unit test, not by the record.

**Why:**

- A record-level invariant would either (a) throw `IllegalArgumentException` if the use case has a bug, hiding the bug behind a generic exception, or (b) silently auto-correct `processed` to equal the sum, masking the bug entirely. Both are worse than the current pattern: the use case is the only constructor, the unit test asserts the relationship, and any future code reading a `SweepReport` does not have to re-validate the inputs every time.
- Records with field-level invariants tend to be tempting in lower modules to construct from arbitrary input. Keeping the record dumb signals "this is a value carrier, not a smart object" and matches the project's pattern (e.g. `SeedReport.Seeded`).

**Alternatives considered:**

- Throw in the constructor when the sum disagrees. Rejected per above.
- Make the record sealed with a sole `from(...)` factory that takes individual counts and returns `Empty` or `Counted`. Overkill — there is no behavioural difference between zero and non-zero counts.

### Decision 6: Add `Accounts.findById(AccountId)` as an additive port method, not a side query through a new port

F10 needs to load an account by `AccountId` (which is what `Movement.accountId()` returns). The current `Accounts` port only exposes `findByNumber(AccountNumber)`. We add `Optional<Account> findById(AccountId id)` to the same port rather than create a new `AccountByIdLookup` interface.

**Why one port, not two:**

- The two methods are different access keys to the same aggregate. Splitting them would require both adapters to implement two interfaces and would force F10 to inject two beans where one will do.
- The application module convention is "one port per aggregate". `Accounts` is the port for `Account` aggregates regardless of which identifier you have.

**Why update the `account-lookup` capability spec at all:**

- `account-lookup` describes the customer-facing HTTP read of an account by external `AccountNumber`. The new `findById(AccountId)` method is not a public API — it's an internal port method consumed by F10's suspend cascade. We update the spec only to note that the `Accounts` port "MAY also be loaded by `AccountId` for internal consumers", because the spec already documents the port shape and we want it to stay accurate.

### Decision 7: No new schema, no new migration, no new repository method

The infrastructure adapter implements `findById(AccountId)` via `repository.findById(id.value())` — `JpaRepository.findById(UUID)` is inherited free. No `@Query`, no new SQL.

**Why this is safe:**

- `AccountEntity`'s primary key is `id` (UUID) which maps directly to `AccountId.value()`. The existing schema already enforces uniqueness; the existing `account` table is exactly what F10 reads.

## Risks / Trade-offs

[Risk] A bug in the suspend cascade leaves an account ACTIVE while the journal that touched it is FAILED. → **Mitigation**: F11 (`balance-drift-detection`) will reconcile cached balance vs ledger sum and re-flag this asymmetry; that's its job. F10 makes a best-effort cascade with per-journal try/catch; F11 catches anything that slipped through. The integration test asserts the happy-path cascade explicitly.

[Risk] A misconfigured `bank.journal-verification.fixed-delay-ms` (e.g. set to 1) generates excessive scheduler load and overwhelms the DB pool. → **Mitigation**: the `JournalVerificationProperties` compact constructor refuses non-positive `fixedDelayMs` and falls back to `10_000` with a single WARN line at bean creation. A pathological value like `1` is technically accepted, but the page size of 50 capping per-tick work means a tight loop still sees diminishing returns (the page query and aggregate-balance query are the bottleneck) — observable through actuator's `/scheduledtasks` and the per-tick summary log.

[Risk] The tick summary log fires every 10 s with `processed=0` in steady state — log noise. → **Mitigation accepted**: this is intentional. The empty tick is the heartbeat; absence of the line is itself a signal (the scheduler stopped). Logback's `WARN` retention policies can downgrade these to a separate audit appender if operators want them out of the main feed.

[Risk] An exception escapes the use case's outer loop (not the per-journal try/catch) and the entire tick aborts mid-page. → **Mitigation accepted**: Spring's `@Scheduled` infrastructure logs the exception at WARN and re-fires the next tick at the configured delay. The unprocessed Pending journals are re-loaded by the next tick's `findByStatus(...)`. No data is lost; the visibility cost is one WARN line per affected tick, which is the correct severity.

[Risk] Under high concurrency a Pending journal is re-loaded by two consecutive ticks if the first tick is still in-flight when the second starts (impossible with `fixedDelay`, but possible if someone refactors to `fixedRate`). → **Mitigation accepted**: the `markVerified()` / `markFailed()` transitions on `JournalEntry` are idempotent in the sense that a second attempt against an already-terminal status throws `IllegalJournalStatusTransitionException`, which the per-journal try/catch swallows into the `errored` counter. The journal stays in its (now-terminal) status. No corruption; a misleading `errored=1` line is the only cost.

[Risk] An operator sees `journal verification tick: processed=2, verified=0, failed=2, errored=0` and panics. → **Mitigation accepted**: that's the correct response. The dashboard / alert pipeline (out of F10's scope) should page the operator. The log line was designed to be the source of truth for that alert.

[Risk] The `accounts.findById(AccountId)` method opens an avenue for application code to load accounts by internal id (instead of public number), potentially leaking internal ids into customer-facing surfaces. → **Mitigation**: F00's ArchUnit `applicationHasNoFrameworkDependencies` doesn't catch this; we rely on code review. The spec note in `account-lookup` explicitly scopes the method to internal consumers.

## Open Questions

None — all decisions are bounded by existing spec requirements and conventions established by F02 / F06 / F08 / F09. No external dependency, no schema, no HTTP-contract decision remains.
