# Balance Drift Detection

## Purpose

A continuous background audit that compares each account's cached balance against the sum of its ledger movements and Suspends any account where the two disagree. Uses a persistent checkpoint over the ledger's monotonic movement id (per [[immutable-ledger]]) so the audit never reprocesses verified history and never misses new history, even across restarts.

## Requirements

### Requirement: Periodic checkpoint-bounded audit

The audit SHALL run continuously on a fixed delay between runs (target: every 30 seconds). The audit SHALL maintain a persistent checkpoint representing the highest ledger movement id that has been audited; the checkpoint SHALL survive restarts.

#### Scenario: Each tick captures floor and ceiling
- **WHEN** an audit tick begins
- **THEN** the audit reads the current checkpoint (the **floor**, exclusive) and captures the current maximum movement id (the **ceiling**, inclusive), so the window is `(floor, ceiling]` and any movements arriving after `ceiling` are deferred to the next tick

#### Scenario: No-op when no new movements
- **WHEN** an audit tick begins and `ceiling <= floor` (no new movements since the previous tick)
- **THEN** the tick performs no further work and exits

#### Scenario: Checkpoint advances atomically with suspensions
- **WHEN** an audit tick completes
- **THEN** the checkpoint advance to `ceiling` and any account suspensions are committed together in one transaction — either both happen or neither

#### Scenario: Restart preserves progress
- **WHEN** the service restarts after one or more audit ticks
- **THEN** the next tick reads the persisted checkpoint and resumes from there, not rescanning movements at or below the saved floor and not skipping movements that arrived while the service was offline

### Requirement: Database-side drift detection

The set of accounts to recheck SHALL be identified by the movements in the window `(floor, ceiling]`. For each such account the audit SHALL recompute the full sum of its ledger movements (across all time, not just the window) via a single query at the data layer, and compare it against the cached balance. The audit SHALL NOT load entire ledger histories into application memory.

#### Scenario: Recompute runs as one query per account
- **WHEN** an audit tick recomputes an account's balance
- **THEN** the recomputation is a single aggregate query against the movements table for that account; no `findAll`/in-memory summation

### Requirement: Drift suspends Active accounts, with clearing-account carve-out

For any account whose cached balance does NOT equal the computed sum AND whose status is currently `Active`, the audit SHALL Suspend the account per [[account-domain]] — except that the **clearing account** (per [[account-opening]] and [[dev-data-seeding]]) SHALL NEVER be Suspended by this audit. An account that is already Suspended or Closed SHALL NOT be re-suspended.

#### Scenario: Drift on an Active account causes suspension
- **WHEN** an Active account's cached balance is changed out-of-band so it no longer matches the sum of its ledger movements, and at least one new movement involving that account exists in the audit window
- **THEN** within one audit cycle the account is Suspended and a high-severity log line names the account

#### Scenario: Clearing account is never auto-suspended
- **WHEN** the clearing account's cached balance is manipulated so it no longer matches its ledger movements
- **THEN** the audit does NOT Suspend the clearing account (it MAY log the discrepancy, but the status remains unchanged)

#### Scenario: Already-Suspended account is not re-processed
- **WHEN** an audit tick examines an account that is already Suspended
- **THEN** no Suspend operation is invoked on it

#### Scenario: No re-suspension on later ticks
- **WHEN** an audit tick has Suspended an account in a previous tick and no new movements have arrived for it
- **THEN** subsequent ticks do not re-detect or re-Suspend the account

### Requirement: Concurrent-safe with live transfers

The audit SHALL NOT interfere with live transfers (which hold locks per [[transfer-locking]]) and live transfers SHALL NOT cause the audit to miss or double-count movements.

#### Scenario: Movements after ceiling roll into next tick
- **WHEN** a transfer commits after the audit has captured `ceiling` for the current tick
- **THEN** the new movements are NOT included in the current tick's window and are picked up by the next tick — they are never missed, never double-counted

### Requirement: Per-tick summary log line

Each audit tick SHALL emit a log entry summarising the window `(floor, ceiling]` and the count of drifted accounts found.

#### Scenario: Per-tick summary
- **WHEN** an audit tick completes
- **THEN** a log line records the floor, ceiling, accounts inspected, and drifted accounts found
