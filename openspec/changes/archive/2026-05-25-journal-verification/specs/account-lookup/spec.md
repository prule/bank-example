## MODIFIED Requirements

### Requirement: Application port stays Spring-free

The `Accounts` interface in `com.bank.core.application.account` SHALL be a plain Java interface with no Spring/JPA annotations and no imports from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`. Its method signatures SHALL use only domain types (`Account`, `AccountNumber`, `AccountId`) and JDK types (`Optional`). The port SHALL declare:

- `Optional<Account> findByNumber(AccountNumber number)` — public lookup by external account number (consumed by the HTTP read endpoint and by F06 / F08 / F09).
- `Optional<Account> findById(AccountId id)` — internal lookup by aggregate id (consumed by [[journal-verification]]'s suspend cascade, which gets `AccountId`s from `Movement` records rather than account numbers). NOT exposed through the HTTP surface; internal-only.
- `Account save(Account account)` — upsert by aggregate id (consumed by F06 / F08 / F09 and [[journal-verification]]).

#### Scenario: Port is plain Java

- **WHEN** the production source of `com.bank.core.application.account.Accounts` is inspected
- **THEN** the interface declares no annotation other than JDK-standard ones, imports nothing from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`, and the existing F00 ArchUnit `applicationHasNoFrameworkDependencies` rule continues to pass

#### Scenario: Save method ships now even though the lookup does not call it

- **WHEN** the `Accounts` port is inspected
- **THEN** it declares both `findByNumber` and `save` so that F06 (fund transfer), F08 (account opening), and F09 (dev data seeding) can land without further port edits

#### Scenario: findById is available for internal id-based loads

- **WHEN** the `Accounts` port is inspected
- **THEN** it declares `Optional<Account> findById(AccountId id)`; the infrastructure adapter implements it by delegating to the existing `JpaRepository.findById(UUID)` with no new SQL and no schema change; the method is NOT reached from any HTTP controller (verified by a grep across `com.bank.core.infrastructure.web..` returning zero references to `Accounts.findById(...)`)

#### Scenario: findById returns empty for an unknown id

- **WHEN** `Accounts.findById(AccountId.of(UUID.randomUUID()))` is called with a UUID that maps to no row
- **THEN** the call returns `Optional.empty()`; no exception is thrown; the call does not log
