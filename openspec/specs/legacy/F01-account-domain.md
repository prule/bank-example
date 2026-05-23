# F01 — Account Domain Rules

## Summary

Defines what an account is, what state it can be in, and the invariants its balance must obey under every mutation. This is the foundation every other feature relies on. Owns the rules — does NOT own persistence, transactions, or APIs.

## User story

As the bank, I want a single, trustworthy model of an account that enforces its own rules so that no caller — whether an API handler, a background job, or a future feature — can put an account into an illegal state.

## In scope

- Account identity (account number, internal id).
- Account status lifecycle.
- Rules for adding money (credit) and removing money (debit).
- Rules for changing status.
- The set of domain errors callers can see.

## Out of scope

- How accounts are stored.
- How accounts are looked up or exposed over an API (F05).
- How accounts are funded on creation (F08).
- Concurrency / locking (F07).

## Functional requirements

- An account has a unique, externally visible **account number** and an internal identifier the caller never needs to know about.
- An account has a **balance** that is never negative and is always represented to currency precision (two decimal places, no rounding surprises).
- An account is always in exactly one **status**: Active, Suspended, or Closed.
- An account is created Active.
- Only Active accounts may be debited or credited.
- A debit decreases the balance by a positive amount; the resulting balance must remain greater than zero. (Note: an attempt that would leave the balance at exactly zero is rejected. See open questions.)
- A credit increases the balance by a positive amount.
- Any attempt to debit or credit a non-Active account is rejected with an "account inactive" error.
- Any attempt to debit or credit a non-positive amount is rejected as invalid input.
- An attempted debit that would violate the non-negative balance rule is rejected with an "insufficient funds" error; the account is unchanged.
- An account may be moved from Active to Suspended and back to Active. Once Closed, status cannot change.
- The account exposes its current state for reading; it does NOT allow callers to set its balance, status, or identifiers directly.

## Acceptance criteria

1. Creating an account with a negative opening balance is rejected.
2. Creating an account succeeds and the new account is Active.
3. Crediting an Active account by a positive amount increases its balance by exactly that amount.
4. Debiting an Active account by an amount strictly less than its balance decreases its balance by exactly that amount.
5. Debiting an Active account by an amount equal to or greater than its balance is rejected with an "insufficient funds" error and the balance is unchanged.
6. Crediting or debiting a Suspended or Closed account is rejected with an "account inactive" error.
7. Crediting or debiting a zero or negative amount is rejected as invalid.
8. An Active account can be Suspended and then Reactivated.
9. A Closed account cannot be Suspended or Reactivated.
10. No code path allows mutating account balance or status except the documented credit/debit/suspend/reactivate operations.

## Dependencies

None. Foundation spec.

## Open questions

- The current rule rejects debits that would leave the balance at exactly zero (not just below zero). This is preserved for behavioural parity with the existing system but is likely a bug. **Decision needed before re-implementing: should an exact-zero balance be allowed?**
- Currency: today there is no currency field. Confirm single-currency is acceptable for the first iteration.
