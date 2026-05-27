## Context

The Bank Core system requires an internal, transactional service to open customer accounts and safely fund them from a configured bank-owned clearing account. 

This design implements the `OpenAccount` plain-Java application use case, the `OpenAccountService` Spring transactional facade service, input validation at the `OpenAccountCommand` record boundary, duplicate account checks, clearing preconditions, and custom domain exceptions.

## Goals / Non-Goals

**Goals:**
- **Atomic Create & Fund**: Coordinate account aggregate instantiation (created with balance zero and status `ACTIVE`) and optional transfer funding inside a single `@Transactional` Spring-managed transaction boundary.
- **Transactional Facade**: Place the Spring `@Service` and `@Transactional` annotations strictly on the infrastructure facade `OpenAccountService`, keeping the application use-case completely framework-free.
- **Input Validation Record**: Use a compact constructor in the `OpenAccountCommand` record to validate non-null values at the application boundary.
- **Configurable Clearing Account**: Inject the clearing account number dynamically via the bootstrap configuration using `@Value("${bank.clearing-account.number}")` (default `CLEARING-000`).
- **Early Duplicate Check**: Pre-check and throw `DuplicateAccountNumberException` early if an account with the requested number already exists, avoiding premature lock acquisition or database writes.
- **Clearing Account Verification**: Ensure that the clearing account exists in the database for positive opening balances, throwing `ClearingAccountMissingException` if missing, while allowing zero-opens to bypass this check.
- **Post-Funding Balance Reload**: Reload the post-funding account aggregate from the `Accounts` port before returning it to guarantee that it accurately reflects the completed ledger balance.

**Non-Goals:**
- **HTTP REST Endpoint**: This change implements the core internal account opening capability. Exposing an external HTTP API endpoint is out of scope.

## Decisions

### 1. Spring-Free Use Case Orchestration
We place `OpenAccount` in `com.bank.core.application.account`:
- Pure Java dependencies: `Accounts`, `TransferFunds`, and `String clearingAccountNumber`.
- Signature: `public Account open(OpenAccountCommand command)`.
- Flow:
  1. Early duplicate account pre-check.
  2. Early clearing account verification (if opening balance > 0).
  3. Create account at balance zero and save.
  4. Perform transfer if opening balance > 0.
  5. Reload from port and return.
- *Rationale*: Confirms Clean Architecture boundaries.

### 2. Early Pre-check Guard Order
- The duplicate account pre-check runs *before* the clearing account check.
- *Rationale*: A duplicate account request should fail on the primary identifier rule regardless of other environment preconditions.

### 3. Facade Transaction Boundary
- We wrap `OpenAccount` with `OpenAccountService` in the `infrastructure` module, marked with `@Service` and `@Transactional`.
- *Rationale*: Confines transaction orchestration to the infrastructure layer, keeping the core application use case pure and easily testable.

### 4. Input Boundary Validation
- `OpenAccountCommand` is a Java record `public record OpenAccountCommand(String number, Money openingBalance)`.
- Compact constructor rejects null inputs throwing `NullPointerException`.
- *Rationale*: Avoids garbage inputs entering the application pipeline, mapping boundary checks to clean JDK standard exceptions.

## Risks / Trade-offs

- **[Risk] High Contention on Clearing Account**: Every positive open updates the clearing account balance, creating a contention bottleneck under heavy account opening loads.
- *Mitigation*: The clearing account is only debited inside transaction boundaries. Lock contention is managed safely by F07's paired locks, failing fast if the acquisition times out.
