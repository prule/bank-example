# Immutable Ledger

## Purpose

Define the append-only accounting record. Every movement of money in the system produces ledger entries that are never modified once written. The ledger — not the cached account balance — is the source of truth for what an account holds.

## Requirements

### Requirement: Journal entry shape and lifecycle

A journal entry SHALL carry a stable identifier, a description, a timestamp, a verification status from the set `Pending`, `Verified`, `Failed`, and an ordered set of movements. A journal entry SHALL begin life as `Pending`. The verification status SHALL transition only `Pending → Verified` or `Pending → Failed`; any other transition (including back to Pending) SHALL be rejected.

#### Scenario: New journal starts Pending
- **WHEN** a journal entry is created with one or more movements and persisted
- **THEN** its status is `Pending` and it carries the supplied identifier, description, timestamp, and movements

#### Scenario: Status transitions are forward-only
- **WHEN** a journal entry's status is changed
- **THEN** only `Pending → Verified` and `Pending → Failed` are accepted; any attempt to move out of `Verified`/`Failed`, or to move back to `Pending`, is rejected

### Requirement: Movements are immutable accounting legs

A movement SHALL record the affected account reference, a strictly positive amount, and whether it is a `debit` (money leaving the account) or a `credit` (money entering the account). Once a movement is persisted, its account reference, amount, and type SHALL NOT be mutable.

#### Scenario: Movement with non-positive amount is rejected
- **WHEN** a movement is constructed with an amount that is zero or negative
- **THEN** construction is rejected

#### Scenario: No mutation API on persisted movements
- **WHEN** the codebase is reviewed for setters or update queries on movements
- **THEN** no path mutates a persisted movement's account, amount, or type

### Requirement: Journal entries are append-only

Once persisted, a journal entry's movements, description, timestamp, identifier, and account references SHALL NOT be mutable. The verification status is the only mutable field on a journal entry, and is governed by the transition rules above.

#### Scenario: No mutation API on persisted journals
- **WHEN** the codebase is reviewed for update or delete queries on journal entries
- **THEN** no path mutates a persisted journal entry's movements, description, timestamp, identifier, or account references

### Requirement: Monotonic movement identifiers

Movement identifiers SHALL be assigned in strictly increasing order across the whole system, regardless of which journal the movement belongs to. Audit features (see [[balance-drift-detection]]) rely on this ordering as a cursor.

#### Scenario: Movement ids are globally monotonic
- **WHEN** two movements M1 and M2 are persisted, M1 before M2
- **THEN** the identifier of M2 is strictly greater than the identifier of M1, regardless of whether they belong to the same journal

### Requirement: Database-side balance check

The data model SHALL support computing whether a persisted journal balances (sum of credit movements equals sum of debit movements) with a single read at the database layer, without iterating movements in application memory.

#### Scenario: Balanced check runs as one aggregate query
- **WHEN** a journal verifier checks whether a journal balances
- **THEN** it issues a single aggregate query against the movements table for that journal id, with no `findAll`/in-memory summation

### Requirement: Journal entries queryable by status

The ledger SHALL support querying journal entries by status with paging, so background sweepers (see [[journal-verification]]) can process journals in bounded ticks.

#### Scenario: Pending journals listed in pages
- **WHEN** a sweeper requests up to N Pending journals
- **THEN** it receives at most N journals, all in `Pending` status, with a stable ordering across calls
