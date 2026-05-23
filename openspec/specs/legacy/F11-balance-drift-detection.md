# F11 — Balance Drift Detection

## Summary

A continuous background audit that compares each account's cached balance against the sum of its ledger movements and Suspends any account where the two disagree. Uses a persistent checkpoint over the ledger's monotonic id so that the audit never reprocesses verified history and never misses new history, even across restarts.

## User story

As the bank, I want the system itself to notice within seconds when an account's stored balance has drifted from the truth in its ledger, and to take that account out of circulation automatically, so that a single bug or partial write cannot quietly compound into customer-visible corruption.

## In scope

- The schedule on which the audit runs.
- The cursor / checkpoint mechanism that bounds each audit window.
- The detection query.
- The containment action when drift is found.
- The exclusion of the clearing account from automatic suspension.

## Out of scope

- Reconciling individual journal entries (that is F10).
- Self-healing — the system does NOT attempt to fix a drifted balance automatically; suspension is the only response.
- Notifying external systems beyond high-severity logs.
- Detection of drift on Suspended or Closed accounts (only Active accounts are checked).

## Functional requirements

- The audit runs continuously on a fixed delay between runs (target: every 30 seconds).
- The audit maintains a **persistent checkpoint** representing the highest ledger movement id that has already been audited. The checkpoint survives restarts.
- On each tick, the audit:
  1. Reads the current checkpoint (the **floor**, exclusive).
  2. Captures the current maximum ledger movement id (the **ceiling**, inclusive). This becomes the fixed upper bound for this tick — movements that arrive after this point are deliberately deferred to the next tick.
  3. If no new movements have appeared since the last tick (`ceiling <= floor`), the tick does nothing and exits.
  4. Identifies the set of accounts that have any ledger movement in the window `(floor, ceiling]`. For each such account, recomputes the full sum of its ledger movements (across all time, not just the window) and compares it against the cached balance.
  5. For any account whose cached balance does not equal the computed sum AND whose status is currently Active, the account is **Suspended** (per F01). The clearing account is excluded from this action.
  6. Advances the checkpoint to the captured ceiling.
- The computation must happen at the data layer in a single query; the application must not load entire ledger histories into memory.
- The audit runs inside one transaction per tick. The advance of the checkpoint and the suspensions are committed together; either both happen or neither.
- A drifted account is suspended exactly once — repeated ticks do not re-suspend already-Suspended accounts.

## Acceptance criteria

1. Starting against a clean, consistent database, the audit runs without flagging any account.
2. Manually corrupting a single account's cached balance (e.g. via SQL UPDATE) causes that account to be Suspended within (target) one audit cycle, and a high-severity log line is emitted naming the account.
3. The clearing account, even if its cached balance is manipulated, is NOT suspended by this audit. (It may be flagged by logs depending on implementation, but it must never be Suspended automatically.)
4. After detection, the audit's checkpoint advances; the next tick does not re-detect the same drift unless a new ledger movement involving the account has happened.
5. Restarting the service does NOT cause the audit to rescan history already covered by the checkpoint, and does NOT cause it to skip movements that arrived while it was offline.
6. While a live transfer is in progress (holding locks per F07), the audit does not interfere with that transfer and the transfer does not interfere with the audit's accuracy — movements committed after the captured ceiling roll into the next tick rather than being missed.
7. An account that is already Suspended is not re-processed by this audit (whether due to status filter or no-op suspension call).
8. The audit emits a log entry summarising the window `(floor, ceiling]` and the count of drifted accounts found each tick.

## Dependencies

- F01 (Account domain) — for the suspension action.
- F02 (Immutable ledger) — for the monotonic movement id used as the cursor and for the sum computation.
- F06 (Fund transfer) — supplies the ledger movements being audited.

## Open questions

- **Reactivation.** Suspension is automatic; recovery is not. We need a documented operational procedure for investigating drift and either correcting the cached balance or invalidating the ledger movement, then reactivating the account. Out of scope for this spec but blocks production use.
- **False positives.** Any bug in transfer logic that updates balance but not the ledger (or vice versa) will trigger this audit. That is the intended behaviour, but it means a production deploy of a new feature must be staged carefully — a regression here suspends customer accounts.
- **Clearing-account exception.** Excluding the clearing account from automatic suspension is intentional (it sits in the funding path and would lock out everything if suspended). Confirm this carve-out is acceptable to the audit/finance stakeholders.
- **Tick interval and pool.** Currently hardcoded to 30s and shares the default scheduler. Externalise both before production.
