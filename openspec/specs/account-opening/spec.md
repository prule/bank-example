# Account Opening

## Purpose

Mechanism for creating a new customer account and, optionally, funding it with an opening balance. Funding always flows from the bank-owned **clearing account** through the standard transfer path (per [[fund-transfer]]), so that no money ever appears in the system without a corresponding ledger entry.

## Requirements

### Requirement: Open account with explicit account number and opening balance

The system SHALL expose an internal account-opening operation that accepts a chosen account number and an opening balance (which MAY be zero). The new account SHALL be created with the chosen account number, status `Active`, and balance zero before any funding step.

#### Scenario: Open with zero balance creates an Active account
- **WHEN** open is called with opening balance zero
- **THEN** a new Active account exists with the chosen account number and balance zero, and NO journal entry is created for it

#### Scenario: Open with negative opening balance is rejected
- **WHEN** open is called with a negative opening balance
- **THEN** the call is rejected per [[account-domain]] and no account is created

#### Scenario: Duplicate account number is rejected
- **WHEN** open is called with an account number that already exists
- **THEN** the call fails and the existing account is unchanged

### Requirement: Funding flows through the clearing account

When the opening balance is greater than zero, the operation SHALL execute exactly one transfer (per [[fund-transfer]]) from the bank's well-known **clearing account** to the new account for the opening amount. The new account SHALL therefore obtain its initial funds through the ledger like any other deposit.

#### Scenario: Positive opening balance funds via a transfer
- **WHEN** open is called with a positive opening balance B
- **THEN** a new Active account exists with balance B, the clearing account's balance is reduced by B, and exactly one journal entry exists with a debit on the clearing account and a credit on the new account, each for amount B

### Requirement: Clearing account is a precondition

The clearing account is a single well-known internal account identified by a fixed account number. Its existence SHALL be a precondition for opening with a positive balance — the opening operation SHALL fail loudly if the clearing account is missing and SHALL NOT create the new account.

#### Scenario: Missing clearing account fails opening
- **WHEN** open is called with a positive opening balance and the clearing account does not exist
- **THEN** the call fails with a clear error and no new account is created

### Requirement: Atomic create + fund

The entire opening operation — create-account plus optional funding-transfer — SHALL run within a single logical transactional boundary. Either the new account exists AND has been funded as requested, or neither effect SHALL be observable.

#### Scenario: Funding failure rolls back account creation
- **WHEN** open is called with a positive opening balance and the funding transfer fails (e.g. clearing account suspended)
- **THEN** the entire opening rolls back: no new account is created and the clearing account's balance is unchanged
