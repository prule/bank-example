# Transfer Locking

## Purpose

Concurrency contract for the transfer path. Two transfers that touch the same pair of accounts in opposite directions must never deadlock, must serialise into a defined order, and must never produce incorrect balances under contention.

## Requirements

### Requirement: Canonical lock order by account number

Before mutating two accounts in one transfer, the operation SHALL acquire an exclusive write lock on each account. Locks SHALL be acquired in a canonical order determined purely by a deterministic comparison of the two account numbers (lower account number first), and never by the order in which the caller provided them.

#### Scenario: Lock order is independent of caller argument order
- **WHEN** thread T1 calls transfer(A, B) and thread T2 calls transfer(B, A) concurrently
- **THEN** both threads attempt to acquire the lock on `min(A, B)` first and then the lock on `max(A, B)`

#### Scenario: Counter-direction concurrent transfers never deadlock
- **WHEN** N (e.g. 100) concurrent transfers fire against the same pair of accounts, half in each direction
- **THEN** all transfers complete within a bounded time (e.g. 10 seconds), no transfer fails with a deadlock-detection error, and the two final balances exactly equal their starting balances

### Requirement: Locks span the surrounding transaction

The locks acquired by a transfer SHALL be held for the full duration of the surrounding transaction and SHALL be released only on commit or rollback. A transfer that cannot acquire a lock SHALL block until it can; failure to acquire within a reasonable bound SHALL become a timeout error.

#### Scenario: Lock released on commit
- **WHEN** a transfer holding locks on accounts A and B commits
- **THEN** the locks on A and B are released and immediately available to other transfers

#### Scenario: Lock released on rollback
- **WHEN** a transfer holding locks on accounts A and B rolls back (exception, crash)
- **THEN** the locks on A and B are released and immediately available to other transfers

#### Scenario: Unrelated accounts proceed without waiting
- **WHEN** a transfer holds locks on accounts A and B while an unrelated transfer on accounts C and D arrives
- **THEN** the C/D transfer proceeds without waiting on A/B's locks

### Requirement: Single lock-acquisition component

The canonical lock-acquisition rule SHALL be enforced by exactly one shared component used by every code path that mutates two accounts simultaneously. Controllers, services, schedulers, and ad-hoc code SHALL NOT call a "lock account for update" primitive directly on their own.

#### Scenario: One source of truth for paired locks
- **WHEN** the codebase is reviewed for code that acquires write locks on accounts
- **THEN** exactly one component is responsible for acquiring the paired locks; no controller, use case, or scheduler calls a lock primitive on its own

### Requirement: Correctness under cross-pair contention

The system SHALL remain correct when a single account is the source for one transfer and the destination for another running simultaneously.

#### Scenario: Cross-pair correctness
- **WHEN** transfer T1 moves money from A to B while transfer T2 simultaneously moves money from C to A
- **THEN** both transfers commit in some serial order, no deadlock occurs, and account A's final balance is the deterministic result of applying both transfers
