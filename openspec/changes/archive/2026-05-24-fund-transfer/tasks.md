## 1. Domain types

- [x] 1.1 Create `com.bank.core.domain.SameAccountTransferException` extending `DomainException`, with `(AccountNumber account)` constructor and `account()` accessor. Message: `"source and destination must differ (both were '<account>')"`.
- [x] 1.2 `SameAccountTransferExceptionTest` (JUnit 5) — accessor round-trip, message contains the account, `extends DomainException`, null rejection.
- [x] 1.3 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson' domain/src/main/` still returns zero matches.

## 2. Application use case

- [x] 2.1 Create `com.bank.core.application.transfer.TransferCommand` as a record `(AccountNumber source, AccountNumber destination, Money amount)`. Compact constructor: non-null all three, and reject zero amount via `Money.isZero()` check (defence-in-depth on top of bean validation).
- [x] 2.2 Create `com.bank.core.application.transfer.TransferFunds` — plain Java, constructor takes `(Accounts, JournalEntries, AccountLocker, Clock)`. Single method `void transfer(TransferCommand command)`:
  - Self-transfer check first (throws `SameAccountTransferException` before any port call).
  - Call `locker.withPairedLocks(command.source(), command.destination(), () -> { ... })` passing original argument order.
  - *Inside the runnable:* load source via `accounts.findByNumber(...)` (throw `ResourceNotFoundException("account", source.value())` on empty), load destination similarly, mutate aggregates via `source.debit(command.amount())` then `destination.credit(command.amount())`, persist via `accounts.save(source)` and `accounts.save(destination)`, build journal entry with description `"Transfer from <src> to <dst>"`, timestamp `clock.instant()`, two movements (DEBIT on source.id() and CREDIT on destination.id()), call `journals.save(entry)`.
  - Class-level Javadoc names the pattern (lock-then-load) and points at F07 and F02.
- [x] 2.3 `TransferFundsTest` (Mockito, JUnit 5) covering: happy path produces one save per aggregate and one journal save with the right description/movements; self-transfer throws and never touches the locker or ports; missing source throws `ResourceNotFoundException` and does not debit/credit; missing destination throws `ResourceNotFoundException` and does not debit/credit; argument order is preserved into the locker; the runnable passed to the locker is the one that does the work (verify via `ArgumentCaptor<Runnable>` invoked manually); `AccountLocker` is invoked *before* `Accounts.findByNumber` (verify with `InOrder`).
- [x] 2.4 `TransferCommandTest` — null rejection per field, zero-amount rejection.
- [x] 2.5 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson' application/src/main/` still returns zero matches.

## 3. OpenAPI contract additions

- [x] 3.1 Create `bootstrap/src/main/resources/openapi/schemas/transfer-request.yaml` with `type: object`, `required: [sourceAccountNumber, destinationAccountNumber, amount]`, properties `sourceAccountNumber` (`type: string`, `minLength: 1`, example `"ACC-001"`), `destinationAccountNumber` (`type: string`, `minLength: 1`, example `"ACC-002"`), `amount` (`type: number`, `minimum: 0.01`, example `25.00`, description noting half-up rounding to 2 decimals).
- [x] 3.2 Create `bootstrap/src/main/resources/openapi/paths/transfers.yaml` with `post:`, `tags: [transfers]`, `operationId: createTransfer`, request body required referencing `TransferRequest`, `responses: 204` (no content), `400` referencing `ErrorEnvelope`, `404` referencing `ErrorEnvelope`.
- [x] 3.3 Edit `bootstrap/src/main/resources/openapi/openapi.yaml`: add `transfers` to `tags:`, add path entry `/api/v1/transfers` → `$ref: ./paths/transfers.yaml`, add `TransferRequest: $ref: ./schemas/transfer-request.yaml` to `components.schemas`.
- [x] 3.4 Run `./gradlew :infrastructure:openApiGenerate` and confirm `com.bank.core.api.TransfersApi` and `com.bank.core.dto.TransferRequest` are generated. Confirm the `amount` field on the DTO is `BigDecimal`-typed with `@NotNull` and `@DecimalMin("0.01")`.

## 4. Controller and mapper

- [x] 4.1 Create `com.bank.core.infrastructure.web.transfer.TransferRequestMapper` as a `@Component`: `TransferCommand toCommand(TransferRequest)` does `new TransferCommand(AccountNumber.of(request.getSourceAccountNumber()), AccountNumber.of(request.getDestinationAccountNumber()), Money.of(request.getAmount()))`.
- [x] 4.2 Create `com.bank.core.infrastructure.web.transfer.TransferController` (`@RestController`) implementing the generated `TransfersApi`. Constructor injects `TransferFunds` and `TransferRequestMapper`. Method `createTransfer(@Valid TransferRequest request)` is annotated `@Transactional`: maps, calls `transferFunds.transfer(command)`, returns `ResponseEntity.noContent().build()`.
- [x] 4.3 Class-level Javadoc on `TransferController` documents that this is the only controller in the codebase carrying `@Transactional` and why (orchestration of multiple aggregate writes inside F07 paired locks).

## 5. Global exception handler

- [x] 5.1 Import `com.bank.core.domain.InsufficientFundsException`, `AccountInactiveException`, `InvalidAmountException`, `SameAccountTransferException` into `GlobalExceptionHandler`.
- [x] 5.2 Add `@ExceptionHandler(InsufficientFundsException.class) handleInsufficientFunds(...)`: log at INFO including the attempted amount and available balance, return `400` with `CodeEnum.INSUFFICIENT_FUNDS` and a human-readable message (do not echo balances into the response body — log only).
- [x] 5.3 Add `@ExceptionHandler(AccountInactiveException.class) handleAccountInactive(...)`: log at INFO, return `400` with `CodeEnum.ACCOUNT_INACTIVE` and a message naming the offending account id and its status.
- [x] 5.4 Add `@ExceptionHandler(InvalidAmountException.class) handleInvalidAmount(...)`: log at INFO, return `400` with `CodeEnum.BAD_REQUEST_PAYLOAD` and the exception's message.
- [x] 5.5 Add `@ExceptionHandler(SameAccountTransferException.class) handleSameAccountTransfer(...)`: log at INFO, return `400` with `CodeEnum.BAD_REQUEST_PAYLOAD` and a message naming the offending account number.
- [x] 5.6 Update `GlobalExceptionHandler` class-level Javadoc: remove the F06-targeted lines (`InsufficientFundsException`, `AccountInactiveException`) from the "future capabilities" TODO list (they're now wired). Leave a note that any future business-rule exception extends `DomainException` and is added the same way.

## 6. Bean wiring

- [x] 6.1 Add a `@Bean Clock systemClock()` method in `BankCoreApplication` returning `Clock.systemUTC()`.
- [x] 6.2 Add a `@Bean TransferFunds transferFunds(Accounts, JournalEntries, AccountLocker, Clock)` method in `BankCoreApplication` constructing the use case with the four injected dependencies.

## 7. Tests

- [x] 7.1 Persistence integration test `bootstrap/src/test/java/com/bank/core/persistence/transfer/TransferAtomicityIntegrationTest` (`@SpringBootTest`, `@ActiveProfiles("test")`): inject `TransferFunds`, `Accounts`, `JdbcTemplate`, `PlatformTransactionManager`. Pre-seed two ACTIVE accounts with starting balances. Happy-path test: invoke via `TransactionTemplate` (or by calling the controller via `TestRestTemplate`), assert both balances change, one journal_entry row with `verification_status='PENDING'`, two ledger_movement rows (one DEBIT, one CREDIT). Failure-path test: use a `@TestConfiguration` to wrap `JournalEntries.save(...)` so it throws after both `Accounts.save` calls have completed; assert rollback restores both balances and inserts zero journal rows.
- [x] 7.2 Controller integration test `bootstrap/src/test/java/com/bank/core/web/transfer/TransferControllerTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, `TestRestTemplate`): one test per spec scenario — 204 happy path; missing source / missing destination / missing amount → 400 BAD_REQUEST_PAYLOAD; zero / negative / below-0.01 amount → 400 BAD_REQUEST_PAYLOAD; self-transfer → 400 BAD_REQUEST_PAYLOAD; missing source account → 404 RESOURCE_NOT_FOUND; missing destination account → 404 RESOURCE_NOT_FOUND; suspended source → 400 ACCOUNT_INACTIVE; suspended destination → 400 ACCOUNT_INACTIVE; overdraw → 400 INSUFFICIENT_FUNDS. For every rejection, also assert `journal_entry` count is unchanged and the source/destination row balances are unchanged.
- [x] 7.3 Extend `OpenApiContractTest`: assert the served document declares the `createTransfer` operation under `/api/v1/transfers` and the `TransferRequest` schema with required fields `sourceAccountNumber`, `destinationAccountNumber`, `amount`.
- [x] 7.4 Verify F00 ArchUnit rules + F07 confinement rules still pass after the change.

## 8. Manifest / open decision

- [x] 8.1 Close `self-transfer` in `openspec/config.yaml` (`closed: true`, `resolution: "Reject as 400 BAD_REQUEST_PAYLOAD via the new SameAccountTransferException; consistent with most banking APIs and avoids ambiguous no-op semantics."`).

## 9. Verification

- [x] 9.1 `./gradlew :domain:test` passes (new `SameAccountTransferExceptionTest`).
- [x] 9.2 `./gradlew :application:test` passes (new `TransferFundsTest`, `TransferCommandTest`).
- [x] 9.3 `./gradlew :infrastructure:test` — NO-SOURCE.
- [x] 9.4 `./gradlew :bootstrap:test` passes — all prior tests plus the two new integration tests.
- [x] 9.5 `./gradlew clean build` — full project green.
- [x] 9.6 `./gradlew :bootstrap:bootRun` + sanity checks via curl:
  - `curl -s http://localhost:8080/v3/api-docs -H 'Accept: application/json' | jq '.paths."/api/v1/transfers".post.operationId'` returns `"createTransfer"`.
  - `curl -s -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/v1/transfers` returns 400 with `code = "BAD_REQUEST_PAYLOAD"`.
  - `curl -s -X POST -H 'Content-Type: application/json' -d '{"sourceAccountNumber":"X","destinationAccountNumber":"Y","amount":1.00}' http://localhost:8080/api/v1/transfers` returns 404 (accounts don't exist; this exercises the lock-then-load + 404 path end-to-end). Stop the app.
