## 1. Domain Extensions

- [x] 1.1 Implement `DuplicateAccountNumberException` in `com.bank.core.domain` extending `DomainException` and containing a `number()` accessor of type `String`.
- [x] 1.2 Implement `ClearingAccountMissingException` in `com.bank.core.domain` extending `DomainException` and containing a `clearingAccountNumber()` accessor of type `String`.

## 2. Application Use Case and Commands

- [x] 2.1 Implement `OpenAccountCommand` (number, openingBalance) as a plain Java record under `com.bank.core.application.account` validating non-null parameters.
- [x] 2.2 Implement Spring-free application use case `OpenAccount` under `com.bank.core.application.account` coordinating early duplicate checks, clearing preconditions, aggregate instantiation, and standard transfer funding.

## 3. Transactional Infrastructure Facade

- [x] 3.1 Implement `OpenAccountService` under `com.bank.core.infrastructure.account` annotated with Spring `@Service` and `@Transactional` delegating to the use case.

## 4. Bootstrap Wiring

- [x] 4.1 Wire the `@Value("${bank.clearing-account.number:CLEARING-000}")` property and register the `OpenAccount` use case bean in `BankCoreApplication`.

## 5. Verification and Testing

- [x] 5.1 Write comprehensive unit tests for command boundary checks, duplicate exceptions, and the `OpenAccount` use case.
- [x] 5.2 Write integration tests in the `bootstrap` module verifying successful create-and-fund transfers, duplicate failures, clearing preconditions, and transaction rollback integrity.
