# F02 — Immutable Ledger

## Summary

Defines the append-only accounting record. Every movement of money in the system produces ledger entries that are never modified once written. The ledger — not the cached account balance — is the source of truth for what an account holds.

## User story

As an auditor (or as a future feature recovering from corruption), I want a complete, append-only history of every cent that ever moved between accounts, so that any current balance can be reconstructed and any disagreement can be investigated.

## In scope

- The shape of a single accounting event (journal entry).
- The shape of an individual movement (ledger movement / ledger leg).
- The verification lifecycle of a journal entry: Pending → Verified or Failed.
- The immutability guarantee.

## Out of scope

- How the ledger is verified or who triggers verification (F10).
- How an account's balance is reconciled against the ledger (F11).
- How transfers create ledger entries (F06).

## Functional requirements

- Money moves are recorded as **journal entries**. Each journal entry represents one logical accounting event (e.g. "transfer X from account A to account B").
- A journal entry carries: a stable identifier, a human-readable description, a timestamp of when it occurred, a verification status, and an ordered set of **movements**.
- A movement records: which account is affected, the positive amount, and whether the movement is a **debit** (money leaving the account) or a **credit** (money entering the account).
- A journal entry is **balanced** if the sum of its credit movements equals the sum of its debit movements. Producing a balanced journal is the responsibility of the feature creating it (e.g. transfer); the ledger itself records what it is given.
- A journal entry begins life as **Pending**. After it has been checked, it is either promoted to **Verified** or marked **Failed**. It never returns to Pending.
- Journal entries and movements, once persisted, are immutable: no mutation API exists. The verification status of a journal entry is the only mutable field, and only forward transitions are allowed.
- Movements have a monotonically increasing identifier assigned in the order they are written. This ordering is used by audit features as a cursor.

## Acceptance criteria

1. A new journal entry is created with status Pending and a non-empty list of movements is attachable to it before it is persisted.
2. Once persisted, no code path mutates a journal entry's movements, description, timestamp, identifier, or account references.
3. Once persisted, no code path mutates a movement's account reference, amount, or type.
4. A journal entry's status can move Pending → Verified or Pending → Failed; no other transitions are permitted.
5. A movement with a zero or negative amount is rejected at construction.
6. Movement identifiers are assigned in strictly increasing order across the whole system, regardless of which journal they belong to.
7. Given any persisted journal, a single read can determine whether it is balanced (sum of credit movements equals sum of debit movements) without iterating in application memory — i.e. the data model supports a database-side computation.
8. The journal can be queried for all entries in a given status, paged.

## Dependencies

None. Foundation spec.

## Open questions

- Should journals support reversal (compensating entry) within this iteration, or is correction strictly an operational action handled outside the system? Today the model supports only forward additions.
- Movement type is currently `DEBIT` / `CREDIT`. Confirm we do not need to distinguish e.g. fee, interest, transfer as a separate dimension yet.
