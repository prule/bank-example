## MODIFIED Requirements

### Requirement: Open account with explicit account number and opening balance

The system SHALL expose an internal account-opening operation (no HTTP endpoint in this change) that accepts a chosen `AccountNumber` and a non-null `Money` opening balance. A zero opening balance SHALL be accepted; a negative opening balance SHALL be rejected by the `Money` invariant per [[account-domain]] before the operation runs. The new account SHALL be created with the chosen account number, status `ACTIVE`, and balance zero before any funding step. The operation SHALL be invoked through `OpenAccountCommand(AccountNumber number, Money openingBalance)` and SHALL return the post-funding `Account` aggregate (reloaded from the [[account-lookup]] `Accounts` port so the returned `balance` reflects any funding transfer that committed).

#### Scenario: Open with zero balance creates an Active account

- **WHEN** `OpenAccount.open(new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.zero()))` is called and no account with number `NEW-001` exists
- **THEN** a new `Account` row exists with number `NEW-001`, status `ACTIVE`, and balance `0.00`; no row is added to `journal_entry`; no row is added to `ledger_movement`; the returned `Account` has number `NEW-001`, status `ACTIVE`, and balance `0.00`

#### Scenario: Open with negative opening balance is rejected at the Money layer

- **WHEN** a caller attempts to construct `new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.of(BigDecimal.valueOf(-1)))`
- **THEN** the `Money.of(...)` call throws (per [[account-domain]]'s non-negative invariant), the `OpenAccountCommand` is never constructed, no row is added to `account`, and no row is added to `journal_entry`

#### Scenario: Returned Account reflects the post-funding balance

- **WHEN** `OpenAccount.open(...)` is called for `NEW-001` with opening balance `50.00` against an existing Active clearing account at balance `1000.00`
- **THEN** the returned `Account.balance()` equals `50.00`, not `0.00`; the returned aggregate is a fresh reload from the `Accounts` port and is not the in-memory aggregate from before the funding transfer

### Requirement: Funding flows through the clearing account via the F06 transfer pipeline

When the opening balance is greater than zero, the operation SHALL execute exactly one transfer (per [[fund-transfer]]) from the configured **clearing account** to the new account for the opening amount, using `TransferFunds.transfer(new TransferCommand(clearingAccountNumber, newAccountNumber, openingBalance))`. The new account SHALL therefore obtain its initial funds through the ledger like any other deposit — every cent ever credited to the account has a matching ledger movement. The argument order passed to `TransferCommand` SHALL be `(source = clearingAccountNumber, destination = newAccountNumber, amount = openingBalance)`; reversing source and destination is forbidden and SHALL be guarded by an explicit unit-test assertion.

#### Scenario: Positive opening balance funds via exactly one transfer

- **WHEN** `OpenAccount.open(...)` is called for `NEW-001` with opening balance `B = 75.00` against an existing Active clearing account at balance `C0 = 1000.00`
- **THEN** a new Active account exists with number `NEW-001` and balance `B`; the clearing account's balance is `C0 - B = 925.00`; exactly one new `journal_entry` row exists with status `PENDING` and exactly two `ledger_movement` rows for that journal — one `DEBIT` against the clearing-account id for amount `B` and one `CREDIT` against the new-account id for amount `B`

#### Scenario: Zero opening balance does not invoke the transfer pipeline

- **WHEN** `OpenAccount.open(...)` is called with opening balance `Money.zero()`
- **THEN** `TransferFunds.transfer(...)` is not invoked, the clearing account is not read, the `journal_entry` row count is unchanged, and the `ledger_movement` row count is unchanged

#### Scenario: Source / destination ordering is fixed

- **WHEN** the production source of `OpenAccount.open(...)` is inspected and the `TransferCommand` it constructs for a positive open is examined
- **THEN** the command's `source()` returns the configured clearing-account number and the command's `destination()` returns the new account's number, never the reverse

### Requirement: Clearing account is a precondition for positive opens only

The clearing account SHALL be a single well-known internal account identified by a configurable account number sourced from the property `bank.clearing-account.number` (default value `CLEARING-000`). Its existence SHALL be a precondition for opening with a positive balance — the operation SHALL fail loudly with `ClearingAccountMissingException` (a new domain exception) if the clearing account does not exist, and SHALL NOT create the new account. Opening with a zero balance SHALL succeed even when the clearing account does not exist, so that the very first account ever opened in a fresh environment (the clearing account itself, opened at zero and topped up later, or seeded directly by [[dev-data-seeding]]) does not trip its own precondition.

#### Scenario: Missing clearing account fails a positive open

- **WHEN** `OpenAccount.open(NEW-001, 50.00)` is called and no row exists in `account` for the configured clearing-account number
- **THEN** the call throws `com.bank.core.domain.ClearingAccountMissingException`; the exception's `clearingAccountNumber()` accessor returns the configured `AccountNumber`; the `account` row count is unchanged; the `journal_entry` row count is unchanged

#### Scenario: Missing clearing account is allowed for a zero open

- **WHEN** `OpenAccount.open(NEW-002, 0.00)` is called and no row exists in `account` for the configured clearing-account number
- **THEN** the call succeeds; a new Active row exists for `NEW-002` at balance zero; no clearing-account row is read; no journal entry is produced

#### Scenario: Clearing-account number is configurable

- **WHEN** the property `bank.clearing-account.number` is set to `MY-CLEARING-XYZ` in `application.yaml` and the application boots
- **THEN** the `OpenAccount` bean is constructed with `clearingAccountNumber = AccountNumber.of("MY-CLEARING-XYZ")`; subsequent positive opens fund from the row whose `account_number` equals `MY-CLEARING-XYZ`

### Requirement: Atomic create-and-fund inside a single transactional boundary

The entire opening operation — create-account plus optional funding-transfer — SHALL run within a single logical transactional boundary. Either the new account exists AND has been funded as requested, or neither effect SHALL be observable. The transactional boundary SHALL be owned by the infrastructure-layer `OpenAccountService` (`@Service @Transactional`) so that callers cannot accidentally invoke the use case outside a transaction; the underlying plain-Java `OpenAccount` use case SHALL remain free of Spring annotations. All writes produced by the operation (one `account` INSERT for the new account, plus — for a positive open — one `account` UPDATE for the clearing account, one `account` UPDATE for the new account, one `journal_entry` INSERT, two `ledger_movement` INSERTs) SHALL share a single JDBC connection inside that boundary.

#### Scenario: Funding failure rolls back account creation

- **WHEN** `OpenAccountService.open(NEW-001, 50.00)` is called against a clearing account whose status is `SUSPENDED`
- **THEN** the F06 debit raises `AccountInactiveException` per [[account-domain]]; the transaction rolls back; no row exists in `account` for `NEW-001`; the clearing account's row is unchanged (balance and status); no row is added to `journal_entry`; no row is added to `ledger_movement`

#### Scenario: Atomicity is enforced by the infrastructure facade, not the application use case

- **WHEN** the production sources are inspected
- **THEN** `com.bank.core.infrastructure.account.OpenAccountService` is annotated `@Service` and `@Transactional` and delegates its single public method straight to the injected `OpenAccount`; `com.bank.core.application.account.OpenAccount` declares no Spring annotations, no `@Transactional`, and no imports from `org.springframework.*` or `jakarta.persistence.*`

#### Scenario: A successful positive open commits exactly one of each affected row

- **WHEN** `OpenAccountService.open(NEW-001, 75.00)` is called against an Active clearing account at balance `1000.00` and `NEW-001` does not exist
- **THEN** at commit time, the `account` row count increases by exactly 1, the `journal_entry` row count increases by exactly 1, and the `ledger_movement` row count increases by exactly 2

### Requirement: Duplicate account number is rejected before any write

The operation SHALL reject a request whose `AccountNumber` already corresponds to an existing row in `account` by throwing `DuplicateAccountNumberException` (a new domain exception carrying the offending `AccountNumber`). The rejection SHALL occur before the new-account INSERT, before any funding transfer attempt, and before any read of the clearing account, so a duplicate request has zero observable side-effects. The F05 unique index on `account.account_number` SHALL remain the concurrent-write safety net — a race that defeats the pre-check still fails the INSERT and rolls back inside the same transactional boundary.

#### Scenario: Duplicate number rejection has no side-effects

- **WHEN** `OpenAccountService.open(EXISTS-001, 50.00)` is called and `EXISTS-001` already exists as an Active account
- **THEN** the call throws `com.bank.core.domain.DuplicateAccountNumberException`; the exception's `number()` accessor returns `AccountNumber.of("EXISTS-001")`; the `account` row for `EXISTS-001` is unchanged; the `account` row count is unchanged; the `journal_entry` row count is unchanged; the clearing account's row is unchanged

#### Scenario: Duplicate pre-check runs before the clearing-account precondition

- **WHEN** `OpenAccountService.open(EXISTS-001, 50.00)` is called and `EXISTS-001` already exists but the clearing account does not
- **THEN** the call throws `DuplicateAccountNumberException`, NOT `ClearingAccountMissingException` — the duplicate check is the first guard

### Requirement: Application use case stays Spring-free

The `OpenAccount` class in `com.bank.core.application.account` SHALL be a plain Java class with no annotations from `org.springframework.*`, no imports from `jakarta.persistence.*`, and no imports from `org.openapitools.*`. Its public method signature SHALL use only domain types (`Account`, `OpenAccountCommand`) and SHALL NOT expose any Spring or persistence type to its callers. Construction SHALL be via a public constructor that accepts the `Accounts` port, the `TransferFunds` use case, and an `AccountNumber clearingAccountNumber`; the bootstrap module SHALL register the bean.

#### Scenario: Use case is plain Java

- **WHEN** the production source of `com.bank.core.application.account.OpenAccount` is inspected
- **THEN** the class declares no annotation other than JDK-standard ones, imports nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and F00's `applicationHasNoFrameworkDependencies` ArchUnit rule continues to pass

#### Scenario: Clearing-account number is injected, not hardcoded

- **WHEN** the `OpenAccount` source is inspected
- **THEN** the clearing-account `AccountNumber` is a final field assigned from a constructor parameter; no string literal naming the clearing account appears in the class body; the property is read by the bootstrap `@Bean` factory method via `@Value("${bank.clearing-account.number}")` and passed to the constructor as an `AccountNumber`

### Requirement: Command record validates inputs at the boundary

`OpenAccountCommand(AccountNumber number, Money openingBalance)` SHALL be a plain Java record. Its compact constructor SHALL reject null fields with `Objects.requireNonNull` (one per parameter, naming the parameter in the message). Zero opening balance SHALL be accepted (the zero-open scenario). Negative opening balance SHALL be impossible to construct because `Money` itself rejects negatives — the command does not need a redundant range check. The record SHALL declare no Spring or persistence imports.

#### Scenario: Null number is rejected

- **WHEN** `new OpenAccountCommand(null, Money.zero())` is called
- **THEN** the call throws `NullPointerException` with a message identifying the `number` parameter

#### Scenario: Null openingBalance is rejected

- **WHEN** `new OpenAccountCommand(AccountNumber.of("NEW-001"), null)` is called
- **THEN** the call throws `NullPointerException` with a message identifying the `openingBalance` parameter

#### Scenario: Zero opening balance constructs successfully

- **WHEN** `new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.zero())` is called
- **THEN** the call returns a valid `OpenAccountCommand` whose `openingBalance()` equals `Money.zero()`

### Requirement: New domain exceptions live alongside existing ones

`com.bank.core.domain.DuplicateAccountNumberException` and `com.bank.core.domain.ClearingAccountMissingException` SHALL be public classes extending `DomainException` (the parent introduced by F01). `DuplicateAccountNumberException` SHALL carry the offending `AccountNumber` as a `number()` accessor. `ClearingAccountMissingException` SHALL carry the configured clearing-account `AccountNumber` as a `clearingAccountNumber()` accessor. Both exceptions SHALL produce a human-readable message that names the offending account number, suitable for inclusion in operator logs.

#### Scenario: DuplicateAccountNumberException carries the offending number

- **WHEN** `new DuplicateAccountNumberException(AccountNumber.of("EXISTS-001"))` is constructed
- **THEN** `getMessage()` contains the substring `"EXISTS-001"`, `number()` returns `AccountNumber.of("EXISTS-001")`, and the class extends `com.bank.core.domain.DomainException`

#### Scenario: ClearingAccountMissingException carries the configured clearing-account number

- **WHEN** `new ClearingAccountMissingException(AccountNumber.of("CLEARING-000"))` is constructed
- **THEN** `getMessage()` contains the substring `"CLEARING-000"`, `clearingAccountNumber()` returns `AccountNumber.of("CLEARING-000")`, and the class extends `com.bank.core.domain.DomainException`
