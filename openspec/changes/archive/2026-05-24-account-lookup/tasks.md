## 1. Domain types

- [x] 1.1 Add `public static Account rehydrate(AccountId id, AccountNumber number, Money balance, AccountStatus status)` to `com.bank.core.domain.Account`, delegating to the private constructor. Reject null arguments with `Objects.requireNonNull` (one per parameter, naming the parameter in the message).
- [x] 1.2 Update `Account` class-level Javadoc: replace the F02 "package-private constructor for persistence rehydration" forward-compat note with documentation that `rehydrate(...)` is the mapper-only entry point (name `com.bank.core.infrastructure.persistence.account.AccountMapper` as the only legitimate caller).
- [x] 1.3 Create `com.bank.core.domain.ResourceNotFoundException` extending `DomainException`, with `(String resourceType, String identifier)` constructor and `resourceType()` / `identifier()` accessors. Message: `"<resourceType> '<identifier>' not found"`.
- [x] 1.4 `AccountRehydrateTest` (JUnit 5) — round-trip for each `AccountStatus` value; null-argument rejection (one assert per parameter).
- [x] 1.5 `ResourceNotFoundExceptionTest` — accessor round-trip, message contains both fields, `extends DomainException`.
- [x] 1.6 Confirm `grep -RE 'org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson' domain/src/main/` still returns zero matches.

## 2. Application port

- [x] 2.1 Create `com.bank.core.application.account.Accounts` interface with `Optional<Account> findByNumber(AccountNumber number)` and `Account save(Account account)`. Javadoc names downstream consumers (F06 fund transfer, F08 account opening, F09 dev data seeding) and links to F02's `JournalEntries` pattern.
- [x] 2.2 Confirm `grep -RE 'org\.springframework|jakarta\.persistence' application/src/main/` still returns zero matches.

## 3. Flyway migration

- [x] 3.1 Create `bootstrap/src/main/resources/db/migration/V3__account.sql` with the `account` table (`id UUID PK`, `account_number VARCHAR(64) NOT NULL`, `balance NUMERIC(19, 2) NOT NULL`, `status VARCHAR(16) NOT NULL`), the two CHECK constraints (`balance >= 0`, `status IN (...)`), and the unique index `idx_account_account_number` on `account_number`.
- [x] 3.2 Run `./gradlew :bootstrap:bootRun` (default profile) to confirm Flyway applies V3 cleanly; tail the log for `Migrating schema "PUBLIC" to version "3 - account"`. Stop the app.

## 4. JPA entity, repository, mapper, adapter

- [x] 4.1 Create `com.bank.core.infrastructure.persistence.account.AccountEntity` annotated `@Entity @Table(name = "account")` with fields `@Id UUID id`, `@Column(name = "account_number", nullable = false, unique = true) String accountNumber`, `@Column(nullable = false, precision = 19, scale = 2) BigDecimal balance`, `@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) AccountStatus status`. Field access; package-private setters used only by the mapper; a JPA-required no-arg constructor.
- [x] 4.2 Create `AccountRepository extends JpaRepository<AccountEntity, UUID>` with `Optional<AccountEntity> findByAccountNumber(String accountNumber)`.
- [x] 4.3 Create `AccountMapper` with `AccountEntity toEntity(Account)` and `Account toDomain(AccountEntity)` (the latter calls `Account.rehydrate(...)`). The `toEntity` path handles both insert (id-not-yet-persisted) and update (id-already-persisted) by setting all four columns from the aggregate.
- [x] 4.4 Create `AccountsJpaAdapter` (`@Component`) implementing `Accounts`. `findByNumber` is `@Transactional(readOnly = true)`. `save` is `@Transactional` and uses `existsById` to decide insert vs update path (avoids Hibernate's "merge" surprise).
- [x] 4.5 Confirm `AccountEntity` resides under `com.bank.core.infrastructure.persistence..` so F00's `jpaEntitiesLiveInInfrastructurePersistence` ArchUnit rule passes.

## 5. OpenAPI contract additions

- [x] 5.1 Create `bootstrap/src/main/resources/openapi/schemas/account-response.yaml` declaring `type: object`, `required: [accountNumber, balance, status]`, properties `accountNumber` (`type: string`), `balance` (`type: string`, `pattern: '^\\d+\\.\\d{2}$'`, `example: "100.00"`), `status` (`type: string`, `enum: [ACTIVE, SUSPENDED, CLOSED]`, `example: ACTIVE`).
- [x] 5.2 Create `bootstrap/src/main/resources/openapi/paths/accounts.yaml` declaring `get:` with `tags: [accounts]`, `operationId: lookupAccount`, path parameter `accountNumber` (`in: path, required: true, schema: { type: string, minLength: 1 }`), 200 response (`AccountResponse`) and 404 response (`ErrorEnvelope`).
- [x] 5.3 Edit `bootstrap/src/main/resources/openapi/openapi.yaml`: add `accounts` to `tags:`, add path entry `/api/v1/accounts/{accountNumber}` → `$ref: ./paths/accounts.yaml`, add `AccountResponse: $ref: ./schemas/account-response.yaml` to `components.schemas`.
- [x] 5.4 Run `./gradlew :infrastructure:openApiGenerate` and confirm the generated `com.bank.core.api.AccountsApi` interface appears under `infrastructure/build/generated/openapi/src/main/java/com/bank/core/api/` and that `com.bank.core.dto.AccountResponse` DTO is generated.

## 6. Controller

- [x] 6.1 Create `com.bank.core.infrastructure.web.account.AccountController` (`@RestController`) implementing the generated `AccountsApi`. Constructor injects `Accounts` and a small `AccountResponseMapper`.
- [x] 6.2 `lookupAccount(String accountNumber)` body: wrap arg in `AccountNumber.of(accountNumber)`, call `accounts.findByNumber(...)`, throw `new ResourceNotFoundException("account", accountNumber)` on `Optional.empty()`, otherwise map to `AccountResponse` and return `ResponseEntity.ok(...)`.
- [x] 6.3 Create `AccountResponseMapper` in the same `web.account` package: `AccountResponse toResponse(Account)` setting `accountNumber = account.number().value()`, `balance = account.balance().value().toPlainString()`, `status = AccountResponse.StatusEnum.valueOf(account.status().name())`. (The generated `StatusEnum` has the three constants because of task 5.1.)

## 7. Global exception handler

- [x] 7.1 Add `@ExceptionHandler(ResourceNotFoundException.class)` method on `GlobalExceptionHandler`: signature `ResponseEntity<ErrorEnvelope> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request)`. Response: HTTP 404, `CodeEnum.RESOURCE_NOT_FOUND`, message `"<resourceType> '<identifier>' not found"` reading from the exception's accessors.
- [x] 7.2 Log at INFO inside the handler: `Resource lookup miss: {} '{}' on {} {}` with resourceType, identifier, method, uri.
- [x] 7.3 Update `GlobalExceptionHandler` class-level Javadoc: remove the `ResourceNotFoundException (F05)` line from the "future capabilities" TODO list (it's now wired); leave the F06-targeted lines (`InsufficientFundsException`, `AccountInactiveException`) intact.

## 8. Tests

- [x] 8.1 Persistence integration test `bootstrap/src/test/java/com/bank/core/persistence/account/AccountsJpaAdapterTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@Transactional`): inject `Accounts` and `JdbcTemplate`; covers `save` → `findByNumber` round-trip across all three statuses, `findByNumber` for a missing number returns `Optional.empty()`, duplicate account_number raises `DataIntegrityViolationException`, `save` of a pre-existing aggregate path (id-already-present) updates rather than inserts.
- [x] 8.2 Controller integration test `bootstrap/src/test/java/com/bank/core/web/account/AccountLookupControllerTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@AutoConfigureMockMvc`, `@Transactional`): pre-seed via `Accounts.save(...)`; assert 200 body has exactly three keys (`accountNumber`, `balance`, `status`) and the values match for an `ACTIVE` account; assert 404 body for a missing number has exactly three keys (`code`, `message`, `timestamp`) with `code = "RESOURCE_NOT_FOUND"` and message naming the missing number; assert a `CLOSED` account returns `"status": "CLOSED"`.
- [x] 8.3 Read-only test in the controller suite: capture `journal_entry` row count and the target account's row state via `JdbcTemplate` before and after a 200 GET; assert both unchanged.
- [x] 8.4 OpenAPI contract assertion in the existing `bootstrap/src/test/java/com/bank/core/web/OpenApiContractTest`: extend to assert the served document contains the `lookupAccount` operation under `/api/v1/accounts/{accountNumber}` and that `components.schemas.AccountResponse.properties.status.enum` includes all three of `ACTIVE`, `SUSPENDED`, `CLOSED`.
- [x] 8.5 Verify F00 ArchUnit rules (`domainHasNoFrameworkDependencies`, `applicationHasNoFrameworkDependencies`, `domainAndApplicationDoNotImportInfrastructureOrConfig`, `jpaEntitiesLiveInInfrastructurePersistence`) and the F07 confinement rules still pass.

## 9. Manifest / open decision

- [x] 9.1 Close `account-status-enum-coverage` in `openspec/config.yaml` (`closed: true`, `resolution: ...`) — the OpenAPI status enum includes all three statuses; no further widening is needed because `account_status` itself is closed at `ACTIVE/SUSPENDED/CLOSED` by F01.

## 10. Verification

- [x] 10.1 `./gradlew :domain:test` passes (new `AccountRehydrateTest` + `ResourceNotFoundExceptionTest`).
- [x] 10.2 `./gradlew :application:test` — NO-SOURCE.
- [x] 10.3 `./gradlew :infrastructure:test` — NO-SOURCE (tests are in bootstrap).
- [x] 10.4 `./gradlew :bootstrap:test` passes — all prior tests plus the four new test classes / scenarios.
- [x] 10.5 `./gradlew clean build` — full project green.
- [x] 10.6 `./gradlew :bootstrap:bootRun` + `curl /actuator/health` — 200 UP confirming V3 migration applied to the default profile.
- [x] 10.7 `curl http://localhost:8080/v3/api-docs | jq '.paths."/api/v1/accounts/{accountNumber}".get.operationId'` returns `"lookupAccount"`.
- [x] 10.8 `curl -i http://localhost:8080/api/v1/accounts/DOES-NOT-EXIST` returns HTTP 404 with `code = "RESOURCE_NOT_FOUND"` and message naming `DOES-NOT-EXIST`. Stop the app.
