# Bank Core — Business Requirements

## 1. Purpose

A core banking service that moves money between customer accounts and keeps an immutable, auditable record of every movement. Each transfer must either succeed in full and be permanently recorded, or fail with no trace on customer balances. The system must be able to prove, at any time, that what a customer sees as their balance is consistent with the history of money that has flowed through their account.

## 2. Scope

In scope:
- Holding customer accounts with a current balance and a status.
- Transferring funds from one account to another.
- Recording every movement of money as an immutable accounting entry.
- Continuously checking that recorded balances match the underlying history.
- Suspending accounts when the history and the balance no longer agree.
- Funding new accounts from a controlled internal source.

Out of scope (for this iteration):
- Customer onboarding, identity, KYC.
- Multiple currencies and foreign exchange.
- Interest, fees, statements, scheduled payments.
- External payment networks (card, ACH, wire, real-time rails).
- Authentication, authorization, multi-tenancy.
- Customer-facing UI.

## 3. Stakeholders

- Account holders — expect their balance to be correct and their transfers to be safe.
- Operations / fraud team — need to see when something has gone wrong and act on it.
- Finance / audit — need a complete, tamper-evident record of every cent that moved.
- Engineering — need to operate the service and reason about its correctness.

## 4. Key Concepts

- **Account** — a named container of money belonging to a customer or to the bank itself. Has a balance and a status.
- **Clearing account** — a single bank-owned internal account used as the funding source when new customer accounts are opened. Money never appears in the system from nowhere; it is moved from the clearing account.
- **Ledger** — the complete, append-only history of money movements. Each movement is recorded as a paired entry (one account loses, another account gains the same amount).
- **Journal entry** — one logical accounting event, made up of the matching debit and credit that together describe a single transfer.
- **Balance** — a fast, cached view of what an account currently holds. Always derivable from the ledger; the ledger wins if they disagree.

## 5. Functional Requirements

### 5.1 Accounts

- Each account has a unique account number used by the outside world to identify it.
- Each account has exactly one of three statuses: **Active**, **Suspended**, or **Closed**.
- Only Active accounts can send or receive money.
- A Suspended account can be returned to Active by a privileged action; a Closed account cannot.
- An account's balance must never be negative.
- Customers (or other services) can look up an account by its account number and see its balance and status.

### 5.2 Account opening

- New customer accounts can be created with a chosen account number and an opening balance.
- If the opening balance is greater than zero, that amount must be transferred from the clearing account through the normal transfer mechanism. New accounts cannot have money created directly into them.
- A newly opened account starts Active.

### 5.3 Transfers

- A transfer moves a positive amount of money from one Active account (source) to another Active account (destination).
- A transfer is **atomic**: either the source is debited, the destination is credited, and the movement is recorded; or none of these things happen.
- The source must have enough money to cover the transfer. If not, the transfer is rejected and nothing changes.
- The destination must be Active. If not, the transfer is rejected and nothing changes.
- Either account being missing causes the transfer to be rejected.
- Successful transfers return no payload — the client should re-read the account if it wants the new balance.
- Failed transfers return a clear, machine-readable reason code (e.g. "insufficient funds", "account inactive", "account not found", "invalid request").

### 5.4 Audit trail

- Every successful transfer produces a journal entry capturing what moved, between which accounts, when, and why.
- Journal entries and the ledger movements they contain are immutable once written. They are never updated or deleted, only superseded by compensating entries (out of scope here, but the design must not foreclose it).
- Every journal entry must be **balanced**: the amounts removed and added across its movements net to zero.
- Each journal entry has a verification status: **Pending** when first created, then promoted to **Verified** once the system has confirmed it balances, or **Failed** if it does not.

### 5.5 Continuous reconciliation

- The system continuously checks that pending journal entries actually balance and promotes them to Verified, or flags them as Failed.
- The system continuously checks that each account's cached balance still equals the sum of its ledger history. Any account where these disagree is automatically Suspended pending human investigation. The clearing account is excluded from automatic suspension.
- These checks must be safe to run alongside live transfers — they must not corrupt or block ongoing customer activity, and they must not miss movements that happen while a check is running.
- These checks must be **resumable**: a restart of the service must not cause history to be re-checked from the beginning, nor cause newly arrived history to be skipped.

### 5.6 Containment on failure

- When a journal entry is found to be unbalanced, every account it touches is Suspended.
- When an account's balance drifts from its ledger history, that account is Suspended.
- Suspension is a one-way action by the system; lifting suspension is a deliberate human decision (out of scope for the first iteration).

## 6. Non-Functional Requirements

### 6.1 Correctness

- Money is never created or destroyed by the system. Every credit has a matching debit of equal amount.
- A transfer either fully succeeds or has no observable effect on balances or the ledger.
- The system must not silently mask a discrepancy between balance and ledger — when in doubt, the affected account is taken out of circulation.

### 6.2 Concurrency & contention

- The system must support many concurrent transfers, including many transfers between the same pair of accounts.
- Two concurrent transfers between the same pair of accounts in opposite directions must never deadlock — they must complete in a defined order.
- High contention on a small number of "hot" accounts must degrade to queuing, not to failure or to incorrect balances.
- Final balances must be independent of the order in which simultaneous transfers happened to be scheduled, provided the net effect of those transfers is the same.

### 6.3 Auditability

- For any account, the bank must be able to reconstruct the current balance purely from the ledger.
- For any journal entry, the bank must be able to confirm that it balances.
- The audit process must be cheap enough to run continuously without operator intervention.

### 6.4 Observability

- Every detected discrepancy (unbalanced journal, drifted balance, suspended account) must be visible in logs at a severity that triggers operational attention.
- Background reconciliation passes must report progress so operators can confirm the system is healthy.
- Errors returned to API clients must carry a stable code for programmatic handling and a human-readable message for support.

### 6.5 Operability

- The service must come up clean from an empty database, optionally seeding a starter set of accounts for development.
- The service must be able to come up against an existing database without re-running checks it has already done.
- Configuration of seeding and any operational toggles must be controllable via environment variables, not code changes.

### 6.6 API contract

- The public API is the source of truth for what callers see. It is defined and published independently of internal code structure.
- Backwards-incompatible changes to the API are an explicit, versioned event.

## 7. Business Rules (Summary)

- Active accounts only may transact.
- Transfer amount must be positive.
- A transfer cannot leave the source with a negative balance.
- The clearing account is the only legitimate source of new money entering customer accounts.
- Every transfer produces exactly one balanced journal entry.
- An account whose balance disagrees with its ledger is unsafe and must be suspended.
- A journal entry that does not balance is unsafe and every account it touches must be suspended.

## 8. Success Criteria

The first iteration is considered complete when:

1. A client can open an account, view it, transfer money to another account, and see the new balances reflected immediately.
2. Concurrent transfers between the same two accounts in opposite directions reliably leave the net balances unchanged and do not deadlock.
3. Attempting to overdraw an account is rejected cleanly with no change to either account.
4. Every successful transfer can be traced in the ledger and corresponds to a Verified journal entry within seconds.
5. Manually corrupting an account's balance causes the system to detect the drift and suspend the account on its own, without operator action.
6. Restarting the service does not cause it to re-process previously verified history, nor to miss new history.

## 9. Risks & Open Questions

- **Idempotency.** Today a retried transfer request produces a duplicate journal entry. A future iteration needs an idempotency key or equivalent.
- **Recovery from suspension.** Suspension is automated; reactivation after investigation is not. We need a defined operational playbook.
- **Closed accounts in API responses.** The published account-status enum needs to include every status the system can actually return, or the system must guarantee Closed accounts are never returned by the lookup endpoint.
- **Multi-currency.** The current model assumes a single implicit currency. Introducing more will touch every layer.
- **External rails.** Real-world money in/out (cards, wires) is out of scope; when added, the clearing account model needs to extend to settlement accounts per rail.
- **Audit cadence vs. customer experience.** The drift detector suspends on disagreement. If a transient bug causes a false positive, customers will be locked out until a human intervenes. The trade-off (safe-by-default vs. availability) needs explicit sign-off from the business.
