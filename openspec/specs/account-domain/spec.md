# Account Domain

## Purpose

Define what an account is, what state it can be in, and the invariants its balance must obey under every mutation. This is the foundation every other capability relies on — it owns the rules but not persistence, transactions, or APIs.

## Requirements

### Requirement: Account identity and state shape

An account SHALL have a unique externally visible account number, an internal identifier, a non-negative balance held to currency precision (two decimal places), and exactly one status from the set `Active`, `Suspended`, `Closed`. A newly created account SHALL be `Active`.

#### Scenario: New account starts Active
- **WHEN** an account is created with a non-negative opening balance
- **THEN** the account exists, its status is `Active`, and its balance equals the opening amount

#### Scenario: Negative opening balance rejected
- **WHEN** an account is created with a negative opening balance
- **THEN** creation is rejected and no account exists

### Requirement: Credit and debit are the only balance mutators

An account SHALL expose `credit` and `debit` operations that take a positive amount; balance, status, and identifiers SHALL NOT be mutable through any other path (no public setter, no reflection-friendly constructor). A debit SHALL decrease the balance only when the resulting balance remains strictly greater than zero; a credit SHALL increase the balance by exactly the amount.

#### Scenario: Credit increases balance
- **WHEN** an Active account with balance B is credited by a positive amount A
- **THEN** the account's balance equals B + A

#### Scenario: Debit within funds succeeds
- **WHEN** an Active account with balance B is debited by an amount A strictly less than B
- **THEN** the account's balance equals B − A

#### Scenario: Debit that would reach zero or below is rejected
- **WHEN** an Active account with balance B is debited by an amount A ≥ B
- **THEN** the operation is rejected with an `insufficient funds` error and the balance is unchanged

#### Scenario: Non-positive amount is rejected
- **WHEN** an Active account is debited or credited with an amount that is zero or negative
- **THEN** the operation is rejected as invalid input and the balance is unchanged

#### Scenario: No path mutates balance outside credit/debit
- **WHEN** the codebase is reviewed for setters or direct field assignments on `Account`
- **THEN** no production code path mutates balance, status, account number, or internal id except via `credit`, `debit`, `suspend`, or `reactivate`

### Requirement: Status transitions

An Account SHALL move between `Active` and `Suspended` in either direction via named operations. Once `Closed`, an Account SHALL NOT transition to any other status.

#### Scenario: Active can be Suspended and Reactivated
- **WHEN** an Active account is suspended, then reactivated
- **THEN** its status moves Active → Suspended → Active

#### Scenario: Closed is terminal
- **WHEN** a Closed account is asked to suspend or reactivate
- **THEN** the operation is rejected and the status remains Closed

### Requirement: Non-Active accounts reject mutations

Any attempt to debit or credit an account whose status is not `Active` SHALL be rejected with an `account inactive` error, regardless of amount.

#### Scenario: Suspended account rejects debit
- **WHEN** a Suspended account is debited by any positive amount
- **THEN** the operation is rejected with `account inactive` and the balance is unchanged

#### Scenario: Closed account rejects credit
- **WHEN** a Closed account is credited by any positive amount
- **THEN** the operation is rejected with `account inactive` and the balance is unchanged
