# F07 — Deadlock-Free Concurrent Transfers

## Summary

Concurrency contract for the transfer path. Two transfers that touch the same pair of accounts in opposite directions must never deadlock, must serialise into a defined order, and must never produce incorrect balances under contention.

## User story

As the operations team, I want concurrent transfers between hot accounts to queue up cleanly and complete correctly, so that the service never goes into a stuck state under load and so that customer balances are never corrupted by races.

## In scope

- The rule used to determine lock-acquisition order between any two accounts.
- The locking mode (exclusive write lock) acquired during a transfer.
- Observable behaviour under contention.

## Out of scope

- Distributed locking across multiple service instances (single-instance assumption).
- Lock-free designs (this spec is explicit that we use row-level locks).
- Locking for read-only endpoints (F05 is read-only and uses no exclusive lock).

## Functional requirements

- Before mutating two accounts in one transfer, the operation must acquire an exclusive lock on each of those two accounts.
- Locks must be acquired in a **canonical order** determined purely by the accounts themselves — specifically by a deterministic comparison of their account numbers — and never by the order in which the caller provided them. The lower account number is always locked first.
- The locks are held for the full duration of the surrounding transaction and released only on commit or rollback.
- A transfer that cannot acquire a lock blocks until it can; failure to acquire a lock within a reasonable bound becomes a timeout error (see Open Questions for the bound).
- The locking rule must be enforced through a single, shared component used by every code path that mutates two accounts simultaneously. Ad-hoc locking in business code is forbidden.
- The system must remain correct when a single account is the source for one transfer and the destination for another running simultaneously.
- Optimistic version checking on the account may also be present as defence-in-depth, but pessimistic locking is the primary mechanism.

## Acceptance criteria

1. **Counter-direction stress.** A test that fires N (e.g. 100) concurrent transfers between the same two accounts, half in each direction, completes within a bounded time (e.g. 10 seconds) with both final balances exactly equal to their starting balances.
2. **No deadlocks.** Across the stress test above, no transfer fails with a deadlock-detection error.
3. **Order independence.** Calling the transfer service with arguments `(A, B)` and `(B, A)` from two threads results in the same canonical lock order being used by both threads.
4. **Single source of truth.** Code review confirms there is exactly one component responsible for acquiring the paired locks; no controller, service, or scheduler directly calls a "lock account for update" primitive on its own.
5. **Same-account read while contended.** While a transfer holds locks on accounts A and B, an unrelated transfer on accounts C and D proceeds without waiting.
6. **Crash safety.** If a transfer holding locks dies or rolls back, the locks release and other transfers proceed.

## Dependencies

- F01 (Account domain). This spec assumes accounts have unique, comparable account numbers.

## Open questions

- **Lock wait timeout.** What is the maximum wait an incoming transfer should accept before failing? Default DB lock-wait behaviour today; an explicit business-level timeout is not configured.
- **Hot-account fairness.** Under sustained contention on a single hot account, transfers serialise. Is queuing acceptable, or do we need a back-pressure / fail-fast response above some threshold? Decision needed before production load.
- **Multi-instance.** The current implementation assumes a single application instance. If we run multiple instances against the same DB, this spec still works because the locks are DB-level. If we ever cache lock decisions in the JVM, this spec breaks.
