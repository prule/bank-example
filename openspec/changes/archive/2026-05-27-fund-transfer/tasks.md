## 1. Domain Extensions

- [x] 1.1 Implement `SameAccountTransferException` in `com.bank.core.domain` extending `DomainException` and containing an `account()` accessor of type `AccountNumber`.

## 2. Application Use Case and Commands

- [x] 2.1 Implement `TransferCommand` (source, destination, amount) as a plain Java value object under `com.bank.core.application.transfer`.
- [x] 2.2 Implement Spring-free application use case `TransferFunds` under `com.bank.core.application.transfer` using `AccountLocker` and `Accounts` to coordinate locking, rehydrating, balance adjustments, journal/movement writes, and persistence.

## 3. Infrastructure Global Exception Mappings

- [x] 3.1 Add an `@ExceptionHandler` in `GlobalExceptionHandler` mapping `SameAccountTransferException` to HTTP `400 BAD_REQUEST_PAYLOAD` and logging at INFO level.
- [x] 3.2 Add an `@ExceptionHandler` in `GlobalExceptionHandler` mapping `AccountInactiveException` to HTTP `400 ACCOUNT_INACTIVE` and logging at INFO level.
- [x] 3.3 Add an `@ExceptionHandler` in `GlobalExceptionHandler` mapping `InsufficientFundsException` to HTTP `400 INSUFFICIENT_FUNDS` and logging at INFO level.

## 4. HTTP Controller and Application Wiring

- [x] 4.1 Define a production-ready `Clock` bean (`Clock.systemUTC()`) in `BankCoreApplication` and register the `TransferFunds` use case as a Spring bean.
- [x] 4.2 Implement `TransferController.transferFunds(...)` in `com.bank.core.infrastructure.web.transfer` implementing the generated `TransfersApi` interface, mapping DTO to command, invoking the use case, and marking the controller method strictly with `@Transactional`.

## 5. Verification and Testing

- [x] 5.1 Write comprehensive unit tests for `SameAccountTransferException` and the `TransferFunds` use case in `domain` and `application` modules.
- [x] 5.2 Create integration tests in the `bootstrap` module verifying successful transfers, payload validations, business exception mapping responses, deadlock contention safety via paired locks, and atomicity/rollback guarantees.
