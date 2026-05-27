## Context

The Bank Core application requires an atomic double-entry fund transfer system. Transfers must safely move balances between accounts under highly concurrent contention, ensure double-entry journal balance invariants at the database level, and roll back cleanly in full on any validation or business-rule violation.

This design implements a spec-compliant REST endpoint `POST /api/v1/transfers` mapping to the OpenAPI generated stub interface. It introduces a clean Java application use case `TransferFunds` that orchestrates same-account checks, paired-locking contention protection via `AccountLocker`, account state transitions, double-entry journal creation, and persistence updates within a single transaction boundary on the controller.

## Goals / Non-Goals

**Goals:**
- **Atomic Balance & Ledger Writes**: Orchestrate debit, credit, and double-entry `JournalEntry` creation such that either all operations succeed or all roll back cleanly with no partial state exposed.
- **Transactional Boundary on Controller**: Apply Spring's `@Transactional` strictly to `TransferController.createTransfer(...)` to encapsulate the entire use case pipeline.
- **Spring-Free Application Use Case**: Implement `TransferFunds` strictly in the application layer (`com.bank.core.application.transfer`) with zero framework, persistence, or OpenAPI DTO dependencies, wired via a `@Bean` factory.
- **Same-Account Transfer Prevention**: Reject self-transfers early with `SameAccountTransferException` to avoid lock contention or duplicate bookkeeping.
- **Canonical Lock Ordering Contentions**: Enforce a "lock-then-load" strategy using F07's `AccountLocker.withPairedLocks(...)` before loading account aggregates from the port.
- **Double-Entry Bookkeeping**: Produce exactly one balanced `PENDING` `JournalEntry` with two movements (`DEBIT` on source, `CREDIT` on destination) matching the transfer amount, carrying a structured description.
- **Time Determinism**: Inject a `java.time.Clock` into `TransferFunds` to ensure predictable and testable timestamps.
- **Standardized Error Mapping**: Catch and translate business exceptions (`AccountInactiveException`, `InsufficientFundsException`, `SameAccountTransferException`) to their respective stable error codes (`ACCOUNT_INACTIVE`, `INSUFFICIENT_FUNDS`, `BAD_REQUEST_PAYLOAD`) logging at `INFO` level.

**Non-Goals:**
- **Asynchronous Ledger Processing**: Promotion of journals from `PENDING` to `VERIFIED` (this is owned by continuous audit sweepers F10).
- **Idempotency Execution**: This capability maps out the core transfer execution pipeline. Routing through the idempotency store via `Idempotency-Key` headers is specified to run the pipeline but is technically detailed under a separate capability [[transfer-idempotency]] (this spec provides the hook but does not implement the idempotency database table).

## Decisions

### 1. Spring-Free Use Case Orchestration
We place the `TransferFunds` use case inside the application layer:
- It coordinates:
  1. Cheap self-transfer check.
  2. Paired lock acquisition via `AccountLocker.withPairedLocks`.
  3. Rehydration and state checks via `Accounts.findByNumber`.
  4. Balance adjustments using domain methods `debit` and `credit`.
  5. Immutable double-entry journal creation with two movements and an description: `"Transfer from <source> to <destination>"`.
  6. Persistence of both accounts and the journal entry.
- *Rationale*: Adheres to Clean Architecture and ensures application logic is pure Java, facilitating unit testing and portability.

### 2. Lock-then-Load Concurrency Strategy
- To protect against race conditions where concurrent transfers read stale balances, the use case will invoke `AccountLocker.withPairedLocks` *before* loading account aggregates.
- We preserve the caller-supplied order when calling `withPairedLocks`. The locker implementation (F07) guarantees canonical lock sorting under the hood to prevent deadlocks.
- *Rationale*: Eliminates the "lost update" problem and prevents cyclic deadlocks by forcing an ordained acquisition order.

### 3. Early Same-Account Short-Circuit
- We execute a cheap reference/string check (`sourceAccountNumber.equals(destinationAccountNumber)`) at the start of the use case.
- If true, it throws `SameAccountTransferException` immediately.
- *Rationale*: Prevents self-transfers from accessing the concurrent lock map or producing nonsensical balanced zero-sum journal entries.

### 4. Transaction Boundary on the Controller
- We place Spring's `@Transactional` on `TransferController.createTransfer(...)`.
- This ensures the database transaction spans: DTO mapping, locking, domain model mutations, database writes, and releases locks on transaction commit or rollback.
- *Rationale*: Encapsulates the entire multi-aggregate database interaction. This is the only transactional method in the controller layer.

### 5. Standardized INFO-level Exception Handling
- The new exception handlers in `GlobalExceptionHandler` log at `INFO` level because business failures (insufficient funds, inactive account, self-transfers) are expected validation results rather than system bugs.
- Mapping:
  - `SameAccountTransferException` → `400 BAD_REQUEST_PAYLOAD`
  - `AccountInactiveException` → `400 ACCOUNT_INACTIVE`
  - `InsufficientFundsException` → `400 INSUFFICIENT_FUNDS`
- *Rationale*: Eliminates excessive log noise on expected API errors while maintaining strict contract mapping.

## Risks / Trade-offs

- **[Risk] High Lock Contention**: Heavy traffic between the same accounts can create transaction blockages.
- *Mitigation*: We perform early domain checks and acquire locks with a configurable timeout (`bank.transfer.lock-wait-ms`, defaulting to 5000ms, and 500ms in tests), ensuring transactions fail fast under extreme load rather than starving resources.
