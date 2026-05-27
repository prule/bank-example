# F06 — Fund Transfer API

## Summary

Endpoint that moves a positive amount of money from one Active account to another Active account atomically, producing exactly one balanced journal entry.

## User story

As a client, I want a single API call that either moves money in full (debiting the source, crediting the destination, recording the movement) or has no effect at all, so that I never have to reason about partial transfers, partial logs, or recovery procedures.

## In scope

- HTTP endpoint definition.
- Atomicity guarantee: balance changes and ledger writes commit together.
- Rejection rules for invalid transfers.
- Production of exactly one balanced journal entry per successful transfer.

## Out of scope

- Concurrency / deadlock prevention — see F07.
- Verification that the journal balances after the fact — see F10.
- Idempotency keys / retry deduplication (see Open Questions).
- Scheduled or recurring transfers.
- Multi-leg / split transfers (one source, multiple destinations).

## Functional requirements

- Endpoint: `POST /api/v1/transfers`. Request body carries source account number, destination account number, and a positive amount.
- On success: returns HTTP 204 No Content. The caller re-reads the affected accounts (F05) if it wants the new balances.
- Validation rules (rejected with `400 BAD_REQUEST_PAYLOAD`):
  - Missing source or destination.
  - Amount missing, zero, or negative. The contract minimum is 0.01.
- Business rules (rejected per F03 mapping):
  - Source or destination not found → `404 RESOURCE_NOT_FOUND`, no change.
  - Either account not Active → `400 ACCOUNT_INACTIVE`, no change.
  - Source has insufficient funds (see F01 for exact rule) → `400 INSUFFICIENT_FUNDS`, no change.
- Atomicity:
  - Either: source debited, destination credited, one journal entry persisted with two movements (debit on source, credit on destination), all committed together.
  - Or: nothing changes — no balance update, no journal entry, no orphaned movements.
- The journal entry's description should identify the source and destination account numbers and the timestamp is the time the transfer was processed.
- The journal entry is persisted as Pending; promotion to Verified is the responsibility of F10.
- Transfers between an account and itself are out of scope — see open questions.

## Acceptance criteria

1. A valid transfer between two Active accounts returns 204 and, on subsequent reads, both balances reflect the move exactly.
2. After a successful transfer, exactly one new journal entry exists with status Pending, containing one debit movement on the source account and one credit movement on the destination account, both for the requested amount.
3. A transfer that would overdraw the source returns 400 `INSUFFICIENT_FUNDS`, both balances are unchanged, and no journal entry is created.
4. A transfer to a Suspended destination (or from a Suspended source) returns 400 `ACCOUNT_INACTIVE`, no balance change, no journal entry.
5. A transfer involving a missing source or destination returns 404 `RESOURCE_NOT_FOUND`, no change, no journal entry.
6. A transfer with amount zero, negative, or absent returns 400 `BAD_REQUEST_PAYLOAD`, no change, no journal entry.
7. Crashing or rolling back mid-flight leaves the system in the pre-transfer state — there is no observable partial transfer.
8. A successful response body is empty.

## Dependencies

- F01 (Account domain).
- F02 (Immutable ledger).
- F03 (API error contract).
- F04 (Contract-first API).
- F07 (Deadlock-free concurrent transfers).

## Open questions

- **Idempotency.** Today a retried request creates a second journal entry and a second debit/credit pair. Production usage will need an idempotency key on the request or another deduplication mechanism. Decision needed for v1.
- **Self-transfers.** Should `source == destination` be explicitly rejected, or silently allowed as a no-op-with-journal? Today the behaviour is undefined.
- **Currency.** Single currency assumed. When multi-currency is introduced, this endpoint will need a currency field and a rejection rule for mismatched accounts.
