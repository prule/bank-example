## Why

Currently, the system lacks the ability to create new customer accounts or initialize them with opening balances. Releasing `account-opening` provides an internal, atomic double-entry balance funding pipeline where initial balances flow from a bank-owned clearing account to the newly opened customer account, ensuring that all money in the system is backed by audited ledger movements.

## What Changes

- **Spring-Free Application Use Case**: Implement the `OpenAccount` plain Java class in `com.bank.core.application.account` coordinating account opening logic without framework dependencies.
- **Transactional Infrastructure Facade**: Implement `OpenAccountService` inside `com.bank.core.infrastructure.account` annotated with Spring's `@Service` and `@Transactional` to establish the transaction boundary for create-and-fund operations.
- **Command Envelope**: Create the plain Java record `OpenAccountCommand` validating that chosen account numbers and opening balances are non-null at the application boundary.
- **Configurable Clearing Account**: Support configurable clearing account numbers via the `bank.clearing-account.number` property (defaulting to `CLEARING-000`), injected cleanly via the bootstrap `@Bean` registration.
- **Early Duplicate Checks**: Validate that the account number does not already exist before performing any writes, failing early to prevent resource locks.
- **Clearing Precondition**: Enforce that the clearing account must exist for positive opening balances, throwing a descriptive exception early if missing, while allowing zero-opens to proceed without it.
- **New Domain Exceptions**: Introduce `DuplicateAccountNumberException` and `ClearingAccountMissingException` in the `domain` module, extending `DomainException`.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `account-opening`: Implement the customer account opening use case, initial clearing account funding transfers, early duplicate checks, transaction boundaries on the infrastructure facade, and new domain exceptions.

## Impact

- **Infrastructure Integration**: Adds `OpenAccountService` facade wired as a transactional entry point.
- **Ledger Invariant Alignment**: Enforces that positive opening balances are processed through standard double-entry ledger transfers (`TransferFunds`), generating matching balanced journal records.
- **Downstream Capability**: Serves as the critical foundation for `dev-data-seeding` (which seeds environments on startup) and future HTTP creation endpoints.
