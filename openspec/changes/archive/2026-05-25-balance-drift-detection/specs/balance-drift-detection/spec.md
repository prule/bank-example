## MODIFIED Requirements

### Requirement: Periodic checkpoint-bounded audit

A scheduled component `BalanceDriftScheduler` SHALL run a single `tick()` method continuously on a fixed delay between runs, with the delay sourced from `bank.balance-drift.fixed-delay-ms` (default `30000`, target: every 30 seconds) and the first run delayed by `bank.balance-drift.initial-delay-ms` (default `15000`). The annotation SHALL be `@Scheduled(fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}", initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}")` — `fixedDelayString` so a slow tick never overlaps with the next. Each tick SHALL delegate to `BalanceDriftAudit.audit()` (the infrastructure-module `@Service @Transactional` facade) which delegates to `DetectBalanceDrift.audit()` (the plain-Java use case). The use case SHALL maintain a persistent checkpoint via the `AuditCheckpoints` port and the `audit_checkpoint(audit_name VARCHAR(64) PK, last_movement_id BIGINT NOT NULL CHECK >= 0)` table created by Flyway migration `V4__audit_checkpoint.sql`; the named checkpoint for this audit SHALL be `"balance_drift"` (the constant `DetectBalanceDrift.AUDIT_NAME`).

#### Scenario: Each tick captures floor and ceiling

- **WHEN** an audit tick begins
- **THEN** the use case calls `checkpoints.readOrZero("balance_drift")` to get the `floor` (exclusive lower bound, initial `0` for a fresh DB) and `movements.currentCeiling()` (a `SELECT COALESCE(MAX(id), 0) FROM ledger_movement` SQL) to get the `ceiling` (inclusive upper bound) so the audit window is `(floor, ceiling]`; any `ledger_movement` row whose id is committed after the `currentCeiling()` query is deferred to the next tick because its id is > ceiling

#### Scenario: No-op when no new movements

- **WHEN** an audit tick begins and `ceiling <= floor` (no new committed movements since the previous tick)
- **THEN** the use case calls `movements.distinctAccountIdsInWindow(...)` ZERO times, no `accounts.findById(...)` calls, no `accounts.save(...)` calls; the checkpoint advance via `checkpoints.save("balance_drift", ceiling)` IS still invoked (writing the same value, see design.md Decision 4); the returned `DriftReport` is `(floor, ceiling, inspected=0, drifted=0)`

#### Scenario: Checkpoint advances atomically with suspensions

- **WHEN** an audit tick completes with `drifted >= 1` (i.e. one or more accounts were suspended)
- **THEN** the `audit_checkpoint.last_movement_id` row update AND every `account.status = 'SUSPENDED'` update commit together inside the single `BalanceDriftAudit.@Transactional` boundary — either all of them or none of them; an exception thrown during any per-candidate processing rolls back ALL of: the checkpoint advance, every prior suspension in the same tick, and the failing operation

#### Scenario: Restart preserves progress

- **WHEN** the service restarts after one or more audit ticks
- **THEN** the next tick's `checkpoints.readOrZero("balance_drift")` returns the `last_movement_id` persisted by the most recent committed tick (which survives the JVM restart because `audit_checkpoint` is a regular Flyway-managed table); the new tick's window starts at that floor; no movement at or below the floor is re-loaded into the candidate set; no movement that committed while the service was offline is skipped (it is between the saved floor and the new `MAX(id)`)

#### Scenario: Property name typo is caught by a reflection test

- **WHEN** the production source of `BalanceDriftScheduler.tick()` is inspected via reflection and the `@Scheduled` annotation's `fixedDelayString()` is read
- **THEN** the placeholder string equals exactly `"${bank.balance-drift.fixed-delay-ms:30000}"` and `initialDelayString()` equals exactly `"${bank.balance-drift.initial-delay-ms:15000}"` — a typo that silently disables the audit fails this reflection assertion

### Requirement: Database-side drift detection

The set of accounts to recheck in a tick SHALL be `movements.distinctAccountIdsInWindow(floor, ceiling)` — a single `SELECT DISTINCT m.account_id FROM ledger_movement m WHERE m.id > :floor AND m.id <= :ceiling` SQL backed by the existing `idx_ledger_movement_account_id` index. For each candidate `AccountId id`, the audit SHALL recompute the canonical balance via `movements.sumSignedAmountForAccount(id)` — a single aggregate SQL `SELECT COALESCE(SUM(CASE WHEN movement_type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE account_id = :id` summing ALL of that account's movements (not just the window's), and compare the result to `account.balance()` from the in-memory aggregate loaded via `accounts.findById(id)`. The audit SHALL NOT call `repository.findAll()`, SHALL NOT iterate `ledger_movement` rows in Java, and SHALL NOT load all `LedgerMovementEntity` instances for an account into memory to sum them.

#### Scenario: Recompute runs as exactly one aggregate query per candidate account

- **WHEN** an audit tick processes a candidate set of N accounts
- **THEN** `movements.sumSignedAmountForAccount(...)` is invoked exactly N times during the tick (verifiable by `Mockito.verify(...).times(N)` in unit tests, or by SQL TRACE logging in integration tests counting `SUM(CASE WHEN movement_type = 'CREDIT'` occurrences); `movements.distinctAccountIdsInWindow(...)` is invoked exactly once; `movements.currentCeiling()` is invoked exactly once; no `repository.findAll()` is called

#### Scenario: All-time sum, not windowed sum

- **WHEN** the per-account sum query is inspected
- **THEN** its `WHERE` clause filters on `account_id = :id` only (no `id > :floor` or `id <= :ceiling` predicate) so the returned sum is the total of every movement ever recorded for that account — matching what the cached `account.balance` claims to represent

### Requirement: Drift suspends Active accounts, with clearing-account carve-out

For each candidate account in the tick's window, the audit SHALL apply the following sequence in order; the clearing account SHALL never be auto-suspended; an account whose status is not `ACTIVE` SHALL never be re-suspended; an Active account whose cached balance does not match the ledger sum SHALL be Suspended (per [[account-domain]]) inside the same transactional boundary as the checkpoint advance. The sequence MUST be:

1. If `accounts.findById(id).isEmpty()` (defensive — the F02 FK constraint makes this unreachable in production), the candidate is silently skipped and does NOT count toward `inspected`.
2. If `account.number().equals(clearingAccountNumber)` (the value of `bank.clearing-account.number`, default `CLEARING-000`), the audit SHALL emit an INFO log line `"clearing-account audit skipped: " + clearingAccountNumber.value() + " (per balance-drift-detection spec carve-out)"` and skip the account WITHOUT performing the balance comparison. The carve-out check happens BEFORE `inspected++` so the clearing account does NOT count toward the inspected total in the summary log.
3. If `account.status() != AccountStatus.ACTIVE` (already SUSPENDED or CLOSED), the audit SHALL skip the account: no `suspend()` call, no `save(account)` call, no log line beyond the per-tick summary; `inspected++` happens (the candidate WAS considered), `drifted` does NOT increment.
4. Otherwise compute `expected = Money.of(max(0, sumSignedAmountForAccount(id)))` (the `max(0, ...)` is defensive — `Money` rejects negatives, and a negative computed sum is itself a corruption signal). If `expected.equals(account.balance())`: in-balance, `inspected++`, continue. Otherwise: `account.suspend()`; `accounts.save(account)`; emit one ERROR log line `"balance drift detected on account " + account.number().value() + " (cached=" + account.balance() + ", expected=" + expected + "); account SUSPENDED"`; `inspected++`, `drifted++`.

#### Scenario: Drift on an Active account causes suspension

- **WHEN** an Active account `CUST-X` has a cached balance of `100.00` but the sum of its ledger movements is `90.00`, and at least one `ledger_movement` row with `account_id = CUST-X.id` exists in the window `(floor, ceiling]`
- **THEN** within one audit tick the account's `account.status` column transitions to `SUSPENDED`; exactly one ERROR-level log line names `CUST-X` and contains both `cached=100.00` and `expected=90.00`; the in-memory `Account` aggregate returned by `accounts.findByNumber("CUST-X")` after the tick reports `status == SUSPENDED`

#### Scenario: Clearing account is never auto-suspended

- **WHEN** the clearing account's `account.balance` is manipulated out-of-band (e.g. `UPDATE account SET balance = balance + 100 WHERE account_number = 'CLEARING-000'`) so it no longer matches the sum of its ledger movements, AND a transfer involving the clearing account commits so its id falls into the next audit window
- **THEN** the audit tick that processes that window logs exactly one INFO line `"clearing-account audit skipped: CLEARING-000 (per balance-drift-detection spec carve-out)"`; the clearing account's `status` column remains `ACTIVE`; no ERROR line names the clearing account; the audit does NOT call `account.suspend()` on the clearing account; the `drifted` count for that tick does not include the clearing account; the `inspected` count for that tick does not include the clearing account either (the carve-out is checked BEFORE `inspected++`)

#### Scenario: Already-Suspended account is not re-processed

- **WHEN** an audit tick examines an account whose `status` is already `SUSPENDED` (e.g. from a prior drift detection)
- **THEN** `account.suspend()` is NOT called; `accounts.save(...)` is NOT called for that account; the `account` row's `status` column is unchanged; the `drifted` counter for that tick does NOT increment for that account; the `inspected` counter DOES increment (the candidate was loaded and evaluated)

#### Scenario: Closed account is skipped exactly like Suspended

- **WHEN** an audit tick examines a candidate whose `status` is `CLOSED`
- **THEN** the audit skips the account exactly like the SUSPENDED case — no `suspend()` call (which would throw `IllegalStatusTransitionException` for CLOSED → SUSPENDED), no `save(account)`, `drifted` does not increment, `inspected` does increment

#### Scenario: No re-suspension on later ticks

- **WHEN** an audit tick has Suspended an account in a previous tick, and no new movements have arrived for that account since
- **THEN** subsequent ticks do not include that account in `distinctAccountIdsInWindow(...)` (because no new movement ⇒ no movement in the window), so the account is never re-loaded, never re-inspected, never re-suspended

#### Scenario: Missing account in candidate set is silently skipped

- **WHEN** the candidate set returned by `distinctAccountIdsInWindow(...)` contains an `AccountId` for which `accounts.findById(id)` returns `Optional.empty()` (impossible in production thanks to F02's FK constraint, but possible in tests that fabricate fixtures)
- **THEN** the audit skips that candidate without throwing; `inspected` does NOT increment; no ERROR log line is emitted; the tick continues to the next candidate

### Requirement: Concurrent-safe with live transfers

The audit SHALL NOT block live F06 transfers and live transfers SHALL NOT cause the audit to miss or double-count movements. The mechanism: (a) the audit's per-account sum is a read-only aggregate against an indexed column, taking no row locks on `ledger_movement`; (b) the audit's `accounts.save(...)` for a drifted account acquires only the row lock for that one account row (an `UPDATE account SET status = ... WHERE id = ?`); (c) the audit's `ceiling` is captured ONCE per tick from `MAX(id)`, and `MAX(id)` returns only committed rows, so a transfer that commits after the audit's `currentCeiling()` call has an id strictly greater than `ceiling` and is therefore deferred to the next tick by the `(floor, ceiling]` window predicate.

#### Scenario: Movements after ceiling roll into next tick

- **WHEN** an audit tick captures `ceiling = 100` and then, before the tick's per-account processing completes, an F06 transfer commits two new `ledger_movement` rows with ids `101` and `102`
- **THEN** the current tick's `distinctAccountIdsInWindow(0, 100)` query does NOT include the accounts touched by the new transfer (the new ids are outside the window); the next tick reads the persisted floor `100` and a new ceiling of at least `102`, so the candidate set includes the affected accounts exactly once; no movement is missed, no movement is double-counted

#### Scenario: Audit does not block concurrent transfers

- **WHEN** an F06 transfer commits while an audit tick is mid-flight processing a different account
- **THEN** the transfer's `account.balance` and `ledger_movement` writes commit without waiting for the audit's transaction (no shared lock, the audit reads movements via index scans and writes only `audit_checkpoint` and any drifted-account `status` columns)

### Requirement: Per-tick summary log line

After `BalanceDriftAudit.audit()` returns, `BalanceDriftScheduler.tick()` SHALL emit exactly one INFO log line whose message template is `"balance drift tick: floor={}, ceiling={}, inspected={}, drifted={}"` with the four fields from the returned `DriftReport`. The line SHALL be emitted on every tick, including the no-op `(floor, ceiling=floor, 0, 0)` heartbeat tick, so absence of the line is a signal that the scheduler stopped. A tick whose use case throws SHALL NOT emit the summary line (the WARN line from Spring's `TaskScheduler` is the operator's signal in that case).

#### Scenario: Per-tick summary line is emitted with the counts

- **WHEN** a tick processes a window where one Active account is in-balance, one Active account drifts, and the clearing account is in the candidate set
- **THEN** the captured INFO log contains exactly one line whose formatted message equals `balance drift tick: floor=<F>, ceiling=<C>, inspected=2, drifted=1` (the clearing account is skipped by the carve-out and does NOT contribute to `inspected` per design.md Decision 6)

#### Scenario: Empty tick still emits the heartbeat

- **WHEN** a tick finds `ceiling == floor` (no new movements)
- **THEN** the captured INFO log contains exactly one line `balance drift tick: floor=<N>, ceiling=<N>, inspected=0, drifted=0`

#### Scenario: Drift detection emits both the ERROR line and the summary line

- **WHEN** a tick detects drift on one account
- **THEN** the captured log contains exactly one ERROR line `balance drift detected on account <number> (cached=<X>, expected=<Y>); account SUSPENDED` AND exactly one INFO line `balance drift tick: floor=..., drifted=1` — both lines from the same tick
