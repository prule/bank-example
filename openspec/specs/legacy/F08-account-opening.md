# F08 — Account Opening

## Summary

Mechanism for creating a new customer account and, optionally, funding it with an opening balance. Funding always flows from the bank-owned **clearing account** through the standard transfer path, so that no money ever appears in the system without a corresponding ledger entry.

## User story

As the bank, when I open a new account for a customer, I want any opening balance to be moved into it from a controlled internal source through the normal transfer mechanism, so that the audit trail is complete and the ledger always balances.

## In scope

- The act of creating a new account record.
- The optional funding step from the clearing account.
- The requirement that the clearing account exists.

## Out of scope

- An HTTP endpoint for account opening — none is exposed in the first iteration. Opening is invoked internally (by seeding, by future tooling).
- KYC / customer identity capture.
- Closing an account.

## Functional requirements

- Opening an account requires a chosen account number and an opening balance (which may be zero).
- A new account is created with the chosen account number and a balance of zero, status Active.
- If the opening balance is greater than zero, a transfer (F06) is executed from the **clearing account** to the new account for the opening amount. The new account therefore obtains its initial funds through the ledger like any other deposit.
- The clearing account is a single, well-known internal account identified by a fixed account number. Its existence is a precondition; opening MUST fail loudly if the clearing account is missing.
- The whole opening operation — create-account + optional funding-transfer — runs in one logical transactional boundary. Either the new account exists AND has been funded, or neither has happened.
- Account opening uses the same F01 domain rules: the new account cannot start with a negative balance.
- Opening the clearing account itself is a special bootstrap action and is out of scope here; see F09.

## Acceptance criteria

1. Calling open with an opening balance of zero results in a new Active account with balance zero and no journal entry created for it.
2. Calling open with an opening balance greater than zero results in:
   - a new Active account with the requested balance,
   - the clearing account's balance reduced by that amount,
   - exactly one journal entry with a debit on the clearing account and a credit on the new account.
3. Calling open when the clearing account does not exist fails with a clear error and no account is created.
4. Calling open with a duplicate account number fails and the existing account is unchanged.
5. Calling open with a negative opening balance is rejected (per F01).
6. If the funding transfer fails for any reason (e.g. clearing account suspended), the entire opening rolls back: no account is created.

## Dependencies

- F01 (Account domain).
- F02 (Immutable ledger).
- F06 (Fund transfer).

## Open questions

- The clearing account's seed balance is set at bootstrap (F09). What is the policy for topping it up in long-running environments? Today the dev seed value is large but finite; in production this needs an operational answer.
- Do we expose a public endpoint for account opening, and if so, what authorisation does it require? Out of scope for now but flagged.
- Should there be one clearing account per ledger purpose (deposits, fees, etc.), or one global clearing account? Currently global.
