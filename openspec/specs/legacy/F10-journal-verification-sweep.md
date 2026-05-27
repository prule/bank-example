# F10 — Pending Journal Reconciliation

## Summary

A continuous background pass that picks up journal entries in **Pending** status, checks that they actually balance (sum of credit movements equals sum of debit movements), and promotes them to **Verified** or marks them **Failed**. Failure cascades to suspending every account the broken journal touched.

## User story

As the bank, I want to be sure that every accounting entry the system has written actually balances, without trusting the writing code to have got it right, so that any bug introducing an unbalanced journal is detected within seconds rather than at the next audit.

## In scope

- The schedule on which the sweep runs.
- The set of journals selected on each sweep tick.
- The check itself.
- The state transitions on the journal.
- The containment action when a journal fails.

## Out of scope

- Reconciling cached account balances against the ledger — that is F11.
- Notifying external systems / alerting beyond logs.
- Replaying or correcting a failed journal (manual / future feature).

## Functional requirements

- The sweep runs continuously on a fixed delay between runs (target: every 10 seconds).
- Only journals in **Pending** status are eligible. Verified and Failed journals are skipped.
- The sweep is bounded per tick: it processes at most a small, configurable page of pending journals per run (target: 50). This keeps each tick predictable and prevents one massive scan from starving live traffic.
- For each eligible journal, the sweep computes whether the journal's movements sum to zero. This must be done at the data layer (e.g. a single aggregate query per journal), NOT by loading all movements into memory and iterating in application code.
- If the journal balances: status moves Pending → Verified. The journal is now considered safe.
- If the journal does NOT balance:
  - Status moves Pending → Failed.
  - A high-severity log line is emitted naming the journal id.
  - Every account referenced by any movement on the failed journal is **Suspended** (per F01), unless it is already Suspended. The suspension is independent of which side of the entry the account sat on.
- The sweep does not retry a Failed journal on subsequent ticks; the Failed state is terminal until human action.
- A sweep tick must be resilient to a single journal's processing failing — one bad journal does not stop the tick from processing the rest of its page.

## Acceptance criteria

1. A balanced journal saved as Pending is observed to move to Verified within (target) one sweep cycle.
2. An unbalanced journal saved as Pending is observed to move to Failed within one sweep cycle, and every account it referenced is Suspended.
3. A journal already in Verified or Failed status is not touched by subsequent sweeps.
4. Manually inserting a large number of Pending journals does not cause any single sweep tick to take longer than a small, bounded time — only the page size of journals is processed per tick.
5. If the balance-checking computation throws on one journal, the remaining journals in the same tick are still processed.
6. The sweep emits a log entry summarising how many journals it processed each tick.
7. Restarting the service does not re-process previously Verified or Failed journals — only journals still in Pending are picked up.

## Dependencies

- F01 (Account domain) — for suspending accounts on failure.
- F02 (Immutable ledger) — for the journal state machine and the database-side balance check.

## Open questions

- The page size and tick interval are currently hardcoded. **Decision needed:** externalise them so operations can tune without redeploying.
- If a journal has been Pending for an unusually long time (e.g. older than N minutes), should it be escalated to a higher-priority log channel even if it eventually verifies? Useful in production; not present today.
- Should the sweep run on a dedicated thread pool, or on the shared default scheduler? Today it shares the default; if F11 and F10 ever collide noisily, this changes.
