## 1. Domain exceptions

- [x] 1.1 Create `domain/src/main/java/com/bank/core/domain/DuplicateAccountNumberException.java` extending `DomainException`. Constructor `(AccountNumber number)`; accessor `AccountNumber number()`; message `"account '" + number.value() + "' already exists"`. Class-level Javadoc names `OpenAccount.open(...)` as the only thrower.
- [x] 1.2 Create `domain/src/main/java/com/bank/core/domain/ClearingAccountMissingException.java` extending `DomainException`. Constructor `(AccountNumber clearingAccountNumber)`; accessor `AccountNumber clearingAccountNumber()`; message `"clearing account '" + clearingAccountNumber.value() + "' does not exist — cannot fund a positive opening balance"`. Class-level Javadoc names this a system-misconfiguration signal (vs. customer-facing 404).
- [x] 1.3 Add `domain/src/test/java/com/bank/core/domain/DuplicateAccountNumberExceptionTest.java` — accessor round-trip, message contains the offending number, `extends DomainException`.
- [x] 1.4 Add `domain/src/test/java/com/bank/core/domain/ClearingAccountMissingExceptionTest.java` — accessor round-trip, message contains the clearing-account number, `extends DomainException`.
- [x] 1.5 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson' domain/src/main/` still returns zero matches.

## 2. Application command record

- [x] 2.1 Create `application/src/main/java/com/bank/core/application/account/OpenAccountCommand.java` as a `record OpenAccountCommand(AccountNumber number, Money openingBalance)`. Compact constructor: `Objects.requireNonNull(number, "number cannot be null")`, `Objects.requireNonNull(openingBalance, "openingBalance cannot be null")`. No range check on `openingBalance` (Money's own invariant rejects negatives at construction).
- [x] 2.2 Add Javadoc explaining: zero opening balance is allowed (the "zero open" scenario), negative is impossible by construction, the command is consumed by `OpenAccount.open(...)`.
- [x] 2.3 Add `application/src/test/java/com/bank/core/application/account/OpenAccountCommandTest.java` — null `number` rejected; null `openingBalance` rejected; zero opening balance accepted; valid construction round-trips both fields.

## 3. Application use case

- [x] 3.1 Create `application/src/main/java/com/bank/core/application/account/OpenAccount.java`. Final class, public constructor `OpenAccount(Accounts accounts, TransferFunds transferFunds, AccountNumber clearingAccountNumber)`. All four constructor params null-checked via `Objects.requireNonNull` with named messages. All four are stored as final fields.
- [x] 3.2 Public method `Account open(OpenAccountCommand command)`:
  1. `Objects.requireNonNull(command, "command cannot be null")`.
  2. If `accounts.findByNumber(command.number()).isPresent()`, throw `DuplicateAccountNumberException(command.number())`.
  3. If `!command.openingBalance().isZero()` and `accounts.findByNumber(clearingAccountNumber).isEmpty()`, throw `ClearingAccountMissingException(clearingAccountNumber)`.
  4. `Account newAccount = Account.open(command.number(), Money.zero())`.
  5. `accounts.save(newAccount)`.
  6. If `!command.openingBalance().isZero()`, call `transferFunds.transfer(new TransferCommand(clearingAccountNumber, command.number(), command.openingBalance()))`.
  7. Return `accounts.findByNumber(command.number()).orElseThrow(() -> new IllegalStateException("just-opened account vanished: " + command.number().value()))` — the `orElseThrow` is defensive; the lookup cannot legitimately miss inside the open transaction.
- [x] 3.3 Class-level Javadoc explains: lock-then-load handled by F06 (not F08); transactional boundary owned by `OpenAccountService` (not the use case itself); the duplicate pre-check is the single-caller deterministic path with the F05 unique index as the concurrent-write safety net; the post-funding reload is required because the in-memory `newAccount` is stale after the F06 credit.
- [x] 3.4 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|org\.openapitools' application/src/main/` still returns zero matches.

## 4. Application unit test

- [x] 4.1 Create `application/src/test/java/com/bank/core/application/account/OpenAccountTest.java`. JUnit 5, Mockito `@ExtendWith`. Construct the use case with mocked `Accounts` and `TransferFunds`, and a fixed `AccountNumber CLEARING = AccountNumber.of("CLEARING-000")`.
- [x] 4.2 Test `zeroOpen_createsActiveAccountAtZero_neverTouchesTransferFunds_neverReadsClearingAccount`:
  - Stub `accounts.findByNumber(NEW).orElse(empty)` for the duplicate pre-check.
  - Stub `accounts.findByNumber(NEW)` (the post-funding reload) to return a freshly-opened aggregate at zero.
  - Verify `accounts.save(...)` called exactly once with an Active aggregate whose number == NEW and balance == zero.
  - Verify `transferFunds.transfer(...)` never invoked.
  - Verify `accounts.findByNumber(CLEARING)` never invoked.
  - Assert returned `Account` has number NEW, status ACTIVE, balance zero.
- [x] 4.3 Test `positiveOpen_clearingPresent_savesNewAccount_thenFundsViaTransferFunds`:
  - Stub `accounts.findByNumber(NEW)` first call (duplicate pre-check) returns empty; stub `accounts.findByNumber(CLEARING)` (precondition) returns an Active clearing aggregate; stub the post-funding reload of NEW to return an Active aggregate at the funded balance.
  - Verify `accounts.save(...)` called exactly once with the freshly-opened (zero-balance) NEW aggregate.
  - Verify `transferFunds.transfer(...)` called exactly once with a `TransferCommand` whose `source() == CLEARING`, `destination() == NEW`, and `amount()` equals the requested opening balance.
  - Assert the returned `Account` is the post-funding reload.
- [x] 4.4 Test `duplicateAccountNumber_throwsDuplicateException_neverSaves_neverInvokesTransfer`:
  - Stub `accounts.findByNumber(NEW)` returns a pre-existing aggregate.
  - Assert `DuplicateAccountNumberException` thrown; `ex.number()` returns NEW.
  - Verify `accounts.save(...)` never invoked; `transferFunds.transfer(...)` never invoked.
- [x] 4.5 Test `positiveOpen_clearingMissing_throwsClearingMissingException_neverSavesNewAccount`:
  - Stub `accounts.findByNumber(NEW)` empty; stub `accounts.findByNumber(CLEARING)` empty.
  - Assert `ClearingAccountMissingException` thrown; `ex.clearingAccountNumber()` returns CLEARING.
  - Verify `accounts.save(...)` never invoked; `transferFunds.transfer(...)` never invoked.
- [x] 4.6 Test `zeroOpen_clearingMissing_isAllowed`:
  - Stub `accounts.findByNumber(NEW)` empty for both the duplicate pre-check and the post-funding reload (return a freshly-opened aggregate on the reload).
  - Do NOT stub `accounts.findByNumber(CLEARING)` — the test asserts it is never invoked.
  - Assert the call succeeds; verify `accounts.findByNumber(CLEARING)` never invoked; verify `transferFunds.transfer(...)` never invoked.
- [x] 4.7 Test `duplicateCheckRunsBeforeClearingAccountCheck`:
  - Stub `accounts.findByNumber(NEW)` returns a pre-existing aggregate; do NOT stub `accounts.findByNumber(CLEARING)`.
  - Assert `DuplicateAccountNumberException` thrown (not `ClearingAccountMissingException`); verify `accounts.findByNumber(CLEARING)` never invoked.
- [x] 4.8 Test `nullCommandRejected` — `assertThrows(NullPointerException.class, () -> useCase.open(null))`.

## 5. Infrastructure transactional facade

- [x] 5.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/account/OpenAccountService.java` annotated `@Service` and `@Transactional`. Constructor injects the `OpenAccount` use case (single arg, stored as final field, null-checked). Single public method `Account open(OpenAccountCommand command)` delegating to `openAccount.open(command)`.
- [x] 5.2 Class-level Javadoc:
  - Names this the transactional shell for F08, the equivalent of F06's `TransferController` for the no-HTTP path.
  - States that ALL F08 callers (F09 seeder, future HTTP controller, integration tests) MUST inject this class, NOT the underlying `OpenAccount` directly — direct injection of the use case loses the `@Transactional` guarantee and breaks the spec's atomicity requirement.
  - References the published spec scenario "Atomicity is enforced by the infrastructure facade, not the application use case".
- [x] 5.3 Confirm the class compiles and the `@Service` makes it picked up by component scan (no `@Bean` factory needed for the facade itself).

## 6. Bootstrap wiring and configuration

- [x] 6.1 Edit `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java`: add `@Bean OpenAccount openAccount(Accounts accounts, TransferFunds transferFunds, @Value("${bank.clearing-account.number}") String clearingAccountNumber)` factory method. Body: `return new OpenAccount(accounts, transferFunds, AccountNumber.of(clearingAccountNumber));`. Method-level Javadoc explains the property read and the AccountNumber wrapping.
- [x] 6.2 Edit `bootstrap/src/main/resources/application.yaml`: add a top-level
  ```
  bank:
    clearing-account:
      number: CLEARING-000
  ```
  block (preserving any existing `bank.transfer.*` keys from F07 — the `bank` parent already exists; add `clearing-account` as a sibling sub-tree).
- [x] 6.3 Edit `bootstrap/src/main/resources/application-test.yaml`: declare the same `bank.clearing-account.number: CLEARING-000` so the test profile is self-documenting (same value as the default).
- [x] 6.4 Boot the app via `./gradlew :bootstrap:bootRun` (default profile); confirm the log shows the application context loading the `OpenAccount` and `OpenAccountService` beans without complaint; stop the app. (The clearing account row does not exist yet — that is F09's job — but bean construction does not require the row.)

## 7. Integration test

- [x] 7.1 Create `bootstrap/src/test/java/com/bank/core/account/OpenAccountServiceIntegrationTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`). Inject `OpenAccountService openAccountService`, `Accounts accounts`, and `JdbcTemplate jdbc`. Add helper methods `seedClearing(Money balance)` and `seedClearing(Money balance, AccountStatus status)` that use `accounts.save(Account.open(AccountNumber.of("CLEARING-000"), balance))` (then `account.suspend()` and re-save if a SUSPENDED status is requested) so each test starts from a known state. Add helpers `countAccounts()` and `countJournals()` reading `SELECT COUNT(*)` from `account` and `journal_entry` via `JdbcTemplate`. Each test method is `@Transactional` so changes roll back between tests.
- [x] 7.2 Test `zeroOpen_createsActiveAccountAtZero_noJournalEntry`:
  - No clearing-account seed.
  - Capture `accountsBefore = countAccounts()`, `journalsBefore = countJournals()`.
  - Call `openAccountService.open(new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.zero()))`.
  - Assert `countAccounts() == accountsBefore + 1`; assert `countJournals() == journalsBefore`; assert the persisted row for `NEW-001` is `ACTIVE` at balance `0.00`.
- [x] 7.3 Test `positiveOpen_createsAccount_fundsViaSingleJournalEntry`:
  - Seed clearing at `1000.00` Active.
  - Capture `accountsBefore` and `journalsBefore`.
  - Call `openAccountService.open(new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.of(new BigDecimal("75.00"))))`.
  - Assert `countAccounts() == accountsBefore + 1`; `countJournals() == journalsBefore + 1`.
  - Assert the persisted row for `NEW-001` is `ACTIVE` at balance `75.00`.
  - Assert the persisted row for the clearing account is `ACTIVE` at balance `925.00`.
  - Assert exactly two `ledger_movement` rows for the new journal entry: one `DEBIT` for `75.00` whose account_id equals the clearing-account row's id, one `CREDIT` for `75.00` whose account_id equals the new-account row's id.
- [x] 7.4 Test `positiveOpen_suspendedClearing_rollsBackEntireOperation`:
  - Seed clearing at `1000.00`, then suspend it (`account.suspend(); accounts.save(account)`).
  - Capture `accountsBefore`, `journalsBefore`, clearing balance / status.
  - Call `openAccountService.open(new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.of(new BigDecimal("50.00"))))` inside `assertThrows(AccountInactiveException.class, ...)`.
  - Assert `countAccounts() == accountsBefore` (the new-account INSERT rolled back); `countJournals() == journalsBefore`; clearing balance and status unchanged; no row exists for `NEW-001`.
- [x] 7.5 Test `duplicateAccountNumber_throwsDuplicateException_noSideEffects`:
  - Seed clearing at `1000.00` Active and pre-seed `EXISTS-001` Active at `200.00` (via `accounts.save(Account.open(...))` plus a manual credit if desired — or just via the helper).
  - Capture `accountsBefore`, `journalsBefore`, the persisted `EXISTS-001` row state.
  - `assertThrows(DuplicateAccountNumberException.class, () -> openAccountService.open(new OpenAccountCommand(AccountNumber.of("EXISTS-001"), Money.of(new BigDecimal("10.00")))))`.
  - Assert exception's `number()` equals `AccountNumber.of("EXISTS-001")`; `countAccounts() == accountsBefore`; `countJournals() == journalsBefore`; `EXISTS-001` row unchanged; clearing row unchanged.
- [x] 7.6 Test `missingClearingAccount_positiveOpen_throwsClearingMissingException_noSideEffects`:
  - Do NOT seed the clearing account.
  - Capture `accountsBefore`, `journalsBefore`.
  - `assertThrows(ClearingAccountMissingException.class, () -> openAccountService.open(new OpenAccountCommand(AccountNumber.of("NEW-001"), Money.of(new BigDecimal("50.00")))))`.
  - Assert exception's `clearingAccountNumber()` equals `AccountNumber.of("CLEARING-000")`; `countAccounts() == accountsBefore`; `countJournals() == journalsBefore`; no row exists for `NEW-001`.
- [x] 7.7 Test `missingClearingAccount_zeroOpen_isAllowed`:
  - Do NOT seed the clearing account.
  - Capture `accountsBefore`, `journalsBefore`.
  - Call `openAccountService.open(new OpenAccountCommand(AccountNumber.of("NEW-002"), Money.zero()))`.
  - Assert `countAccounts() == accountsBefore + 1`; `countJournals() == journalsBefore`; the persisted row for `NEW-002` is `ACTIVE` at `0.00`.
- [x] 7.8 Test `transactionalAnnotationPresentOnFacade`:
  - Reflectively inspect `OpenAccountService.class` and assert `@Transactional` is present on the class (`OpenAccountService.class.isAnnotationPresent(Transactional.class)`).
  - This is the spec scenario "Atomicity is enforced by the infrastructure facade, not the application use case" made executable.

## 8. Boundary verification

- [x] 8.1 Run the existing F00 ArchUnit test class and confirm all rules pass: `domainHasNoFrameworkDependencies`, `applicationHasNoFrameworkDependencies`, `domainAndApplicationDoNotImportInfrastructureOrConfig`, `jpaEntitiesLiveInInfrastructurePersistence`, plus the F07 concurrency confinement rules.
- [x] 8.2 Confirm the spec scenario "Use case is plain Java" by inspecting `OpenAccount.java` and verifying no `import org.springframework.*`, no `import jakarta.persistence.*`, no `import org.openapitools.*`.
- [x] 8.3 Confirm the spec scenario "Clearing-account number is injected, not hardcoded" by inspecting `OpenAccount.java` for the absence of any string literal equal to `"CLEARING-000"` and the presence of a `final AccountNumber clearingAccountNumber` field assigned from a constructor parameter.

## 9. Verification

- [x] 9.1 `./gradlew :domain:test` passes (new `DuplicateAccountNumberExceptionTest` + `ClearingAccountMissingExceptionTest`).
- [x] 9.2 `./gradlew :application:test` passes (new `OpenAccountCommandTest` + `OpenAccountTest`).
- [x] 9.3 `./gradlew :infrastructure:test` — NO-SOURCE.
- [x] 9.4 `./gradlew :bootstrap:test` passes — all prior tests plus the new `OpenAccountServiceIntegrationTest`.
- [x] 9.5 `./gradlew clean build` — full project green; all ArchUnit rules pass; OpenAPI generator runs unchanged.
- [x] 9.6 `./gradlew :bootstrap:bootRun` (default profile) + `curl /actuator/health` returns 200 UP, confirming the `OpenAccount` `@Bean` factory and the `OpenAccountService` `@Service` both wire successfully; stop the app.
