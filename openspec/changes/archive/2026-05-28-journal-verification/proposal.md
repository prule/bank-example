## Why

To guarantee double-entry balance integrity and prevent silent ledger corruption, the system needs an automated, continuous, and highly resilient background sweep. This change implements the `journal-verification` capability to periodically scan `PENDING` journals, verify database-side balance constraints, promote balanced entries to `VERIFIED`, mark unbalanced ones as `FAILED`, and automatically suspend any affected customer accounts.

## What Changes

- **Background Scheduler**: Add a Spring-bound `JournalVerificationScheduler` running a non-overlapping periodic sweep with configurable delay and page size.
- **Spring-Free Sweep Use Case**: Implement the plain-Java use case `VerifyPendingJournals` with per-journal resilience try-catch boundaries, ensuring one corrupted journal does not halt the entire tick.
- **Database-Side Balance Checks**: Define an optimized JPA aggregate aggregate query `isBalanced` in the `JournalEntries` repository port to compute movement balance on the database side rather than loading all movements into the JVM.
- **Idempotent Suspend Cascade**: When a journal fails, walk and deduplicate affected account IDs in encounter order. For active accounts, transition their status to `SUSPENDED` and persist; closed or already-suspended accounts are safely skipped without domain rule violations.
- **Heartbeat & Fail-Fast Logging**: Emit a heartbeat INFO summary log on every tick (including empty ticks) for operator monitoring, and raise ERROR-level alerts for failed journals.

## Capabilities

### New Capabilities
- `journal-verification`: Periodic, bounded, and database-optimized verification of pending ledger journals, cascading failure suspensions to compromised customer accounts.

### Modified Capabilities
<!-- No modified requirements for existing capabilities -->

## Impact

- **Affected Components**:
  - `:bootstrap` module: Will house the scheduled `JournalVerificationScheduler` Spring component.
  - `:application` module: Will house the Spring-free use case `VerifyPendingJournals` and its `SweepReport`.
  - `:infrastructure` module: Will implement the database-side aggregate query `isBalanced(...)` in `JournalEntriesJpaAdapter` and its JPA repository.
- **APIs and Properties**:
  - Configurable properties introduced: `bank.journal-verification.fixed-delay-ms` (default 10000), `bank.journal-verification.initial-delay-ms` (default 5000), and `bank.journal-verification.page-size` (default 50).
