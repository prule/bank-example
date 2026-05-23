# Journal Verification

## Purpose

A continuous background pass that picks up journal entries in `Pending` status (per [[immutable-ledger]]), checks that they balance, and promotes them to `Verified` or marks them `Failed`. Failure cascades to Suspending every account the broken journal touched.

## Requirements

### Requirement: Periodic, bounded sweep

The sweep SHALL run continuously on a fixed delay between runs (target: every 10 seconds). Each tick SHALL process at most a small, configurable page of `Pending` journals (target: 50). `Verified` and `Failed` journals SHALL NOT be reconsidered.

#### Scenario: Pending journal moves to Verified within one tick
- **WHEN** a balanced journal is persisted as `Pending`
- **THEN** within one sweep cycle (target ≤ 10 seconds) it transitions to `Verified`

#### Scenario: Tick is bounded in size
- **WHEN** the system has many more than `page-size` Pending journals
- **THEN** any single tick processes at most `page-size` of them; no tick takes a time proportional to the full backlog

#### Scenario: Verified and Failed are not retouched
- **WHEN** a sweep tick runs after some journals are already `Verified` or `Failed`
- **THEN** those journals are skipped — the sweep selects only `Pending` journals

### Requirement: Database-side balance check

The balance check SHALL be performed at the data layer (e.g. a single aggregate query per journal). The sweep SHALL NOT load all movements into memory and iterate in application code.

#### Scenario: Check runs as an aggregate query
- **WHEN** the sweeper checks whether a journal balances
- **THEN** it issues an aggregate query against the movements table for that journal id; no `findAll` or in-memory iteration

### Requirement: Unbalanced journal fails and suspends every referenced account

If a journal does NOT balance, its status SHALL move `Pending → Failed`, a high-severity log line SHALL be emitted naming the journal id, and every account referenced by any movement on that journal SHALL be Suspended (per [[account-domain]]) regardless of which side of the entry the account sat on, unless the account is already Suspended. The Failed state is terminal — subsequent ticks SHALL NOT retry it.

#### Scenario: Unbalanced journal moves to Failed and suspends touched accounts
- **WHEN** an unbalanced journal exists in `Pending`
- **THEN** within one sweep cycle it transitions to `Failed`, a high-severity log line names the journal id, and every account referenced by any of its movements is Suspended

#### Scenario: Failed journal is not retried on later ticks
- **WHEN** a sweep tick runs after a journal is `Failed`
- **THEN** the Failed journal is skipped and remains Failed

### Requirement: Per-journal resilience

A single journal whose processing throws SHALL NOT stop the tick from processing the remaining journals in its page.

#### Scenario: One bad journal does not stop the tick
- **WHEN** the balance computation throws on a single journal in a tick
- **THEN** the remaining journals in the same tick's page are still processed, and the failing journal's error is logged

### Requirement: Tick emits a summary log line

Each sweep tick SHALL emit a log entry summarising how many journals it processed.

#### Scenario: Per-tick summary line
- **WHEN** a sweep tick completes
- **THEN** a log line records how many journals were processed, how many verified, and how many failed in that tick
