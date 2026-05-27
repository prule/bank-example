## Context

Ledger integrity relies on every journal entry maintaining balanced double-entry movements (credits sum to debits). A continuous background pass is required to identify and verify pending journal entries, fail unbalanced ones, and suspend compromised customer accounts to prevent further transacting.

## Goals / Non-Goals

**Goals:**
- **Periodic Bounded Sweep**: Implement a Spring `@Scheduled` background worker (`JournalVerificationScheduler`) running non-overlapping ticks with a configurable delay and page size.
- **Spring-Free Business Logic**: Define `VerifyPendingJournals` as a pure Java application use case.
- **Optimized SQL Balance Check**: Query movement balances strictly on the database side via aggregate query to avoid hydrating and looping over massive collections in JVM memory.
- **Resilient Page Iteration**: Wrap each journal's verification inside a try-catch to ensure a database exception or domain error on one entry does not abort processing for subsequent entries in the same page.
- **Liveness Monitoring**: Emit heartbeat INFO logs on every tick (including empty ticks), and raise ERROR-level alerts for failed journals.

**Non-Goals:**
- Automatic correction of unbalanced journals (suspending accounts is the correct safety containment).

## Decisions

### 1. Spring-Free Use Case Orchestration
We will write the `VerifyPendingJournals` usecase under `:application` module package `com.bank.core.application.ledger`.
- *Signature*: `public SweepReport sweep(int pageSize)`
- *Dependencies*: `JournalEntries` and `Accounts` ports.
- *Decoupling*: Zero Spring annotations, making it easily testable with simple JUnit test stubs.

### 2. Spring-Bound Scheduler Shell
We will place `JournalVerificationScheduler` inside the `:bootstrap` module package `com.bank.core.bootstrap`.
- It will inject `VerifyPendingJournals` and run a `@Scheduled(fixedDelayString = "${bank.journal-verification.fixed-delay-ms:10000}", initialDelayString = "${bank.journal-verification.initial-delay-ms:5000}")` task.
- By using `fixedDelayString` (not `fixedRateString`), we guarantee that Spring Boot runs ticks sequentially, preventing a long-running tick from overlapping with the next one.

### 3. Database-Side Aggregate Balance Verification
We will reuse the optimized aggregate native query `calculateBalanceDifference` already defined on the JPA repository:
- `SELECT COALESCE(SUM(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE journal_entry_id = :id`
- Returns `BigDecimal` and is considered balanced if its comparison to `BigDecimal.ZERO` returns `0`.

### 4. Deduplicated Suspend Cascade
When an unbalanced journal is failed:
- We will retrieve `entry.getMovements()` and deduplicate `AccountId`s using a `LinkedHashSet<AccountId>` to preserve encounter order.
- For each unique `AccountId`, we load the account. If active, we suspend and save it. closed or already-suspended accounts are skipped to avoid throwing status transition exceptions.

## Risks / Trade-offs

- **[Risk] High volume of Pending journals overloading a single tick**:
- *Mitigation*: The page size is configurable via `bank.journal-verification.page-size` (default 50), ensuring each tick remains bounded and executes within memory limits.
