## Why

F05 just shipped the `account` table, the `Accounts` port, and the `AccountsJpaAdapter`. F07 already provides the `AccountLocker`. F02 provides the `JournalEntries` port and the immutable ledger. F03 provides the canonical error envelope and `GlobalExceptionHandler`. F04 provides the OpenAPI generation pipeline. F01 provides the `Account` aggregate with `credit`/`debit` mutators and the exception types `InsufficientFundsException`, `AccountInactiveException`, `InvalidAmountException` that the handler still needs to map.

F06 is the central capability the manifest groups under "core_concepts: journal-ledger": it composes everything that came before into the *only* HTTP write surface the service has. `POST /api/v1/transfers` debits a source, credits a destination, persists one balanced journal entry, and either commits all three changes or has zero observable effect. Once F06 ships, the service can do its primary job; F08 (account opening) reuses the same pipeline to fund new accounts from the clearing account, and F10/F11 audit the journal entries this endpoint produces.

This change also closes the manifest's `self-transfer` open decision in favour of "reject as `BAD_REQUEST_PAYLOAD`" — the safer default — and explicitly leaves the `idempotency` open question deferred (no `Idempotency-Key` header until a future change). The `lock-wait-timeout` open question was closed by F07; this change consumes its result without re-litigation.

## What Changes

- Introduce the `TransferFunds` use case in `application/src/main/java/com/bank/core/application/transfer/` as a plain Java class — constructor takes `Accounts`, `JournalEntries`, `AccountLocker`, `Clock`. Single public method: `void transfer(TransferCommand command)`. The use case orchestrates: same-account rejection → paired-lock acquisition → load both aggregates inside the locked region → debit / credit → save both aggregates → create and save the balanced journal entry. Lock-then-load (not load-then-lock) prevents the read aggregate from going stale relative to the locked region.
- Introduce `TransferCommand` as a plain Java record in the same package: `(AccountNumber source, AccountNumber destination, Money amount)`. Non-null and Money-positivity invariants enforced at the record's compact constructor.
- Introduce `SameAccountTransferException` in `com.bank.core.domain` extending `DomainException`, carrying the offending `AccountNumber`. F03's handler maps it to `400 BAD_REQUEST_PAYLOAD`. This closes the manifest's `self-transfer` open decision in favour of "reject."
- Extend `GlobalExceptionHandler` with four new `@ExceptionHandler` entries — one method per exception type so the mapping is easy to read at a glance:
  - `InsufficientFundsException` → `400 INSUFFICIENT_FUNDS` (carries the attempted amount and the available balance for the log line).
  - `AccountInactiveException` → `400 ACCOUNT_INACTIVE`.
  - `InvalidAmountException` → `400 BAD_REQUEST_PAYLOAD` (defence-in-depth — bean-validation should catch malformed amounts first, but a runtime path through `Money.of(...)` could still throw).
  - `SameAccountTransferException` → `400 BAD_REQUEST_PAYLOAD`.
  Each entry logs at INFO (not ERROR) — these are *expected* business-rule rejections and operators should not be paged for them. The F03 handler's class-level Javadoc is updated to remove the now-fulfilled F06 TODOs.
- Extend the OpenAPI contract:
  - Add `bootstrap/src/main/resources/openapi/paths/transfers.yaml` declaring `POST /api/v1/transfers` with `operationId: createTransfer`, request body referencing `TransferRequest`, 204 success, and the standard 400/404 error responses referencing `ErrorEnvelope`.
  - Add `bootstrap/src/main/resources/openapi/schemas/transfer-request.yaml` with required fields `sourceAccountNumber` (string, `minLength: 1`), `destinationAccountNumber` (string, `minLength: 1`), and `amount` (`type: number`, `minimum: 0.01`). The generator emits a `BigDecimal` field with `@NotNull` + `@DecimalMin("0.01")` annotations, so bean validation catches missing fields and non-positive amounts and Spring routes the failure through F03's existing validation handler — no new code needed for "missing source" or "non-positive amount" scenarios.
  - Wire both refs into the root `openapi.yaml`: register the new path entry, register `TransferRequest` under `components.schemas`, and add a new `transfers` tag with a one-line description.
- Implement `TransferController` in `infrastructure/src/main/java/com/bank/core/infrastructure/web/transfer/` (`@RestController`) implementing the generated `TransfersApi`. The handler method is `@Transactional` — this is the transaction-scoped boundary the F07 `AccountLocker` requires, and it's the *only* place in this branch where `@Transactional` lives on a controller (justified by F06 being the sole HTTP write endpoint that orchestrates multiple aggregate writes inside paired locks). The controller delegates to a thin `TransferRequestMapper` for DTO-to-command translation, then calls `transferFunds.transfer(command)`. Returns `ResponseEntity.noContent().build()` on success.
- Register `TransferFunds` and a `Clock systemClock()` bean in `BankCoreApplication` via `@Bean` factory methods, keeping bootstrap as the single wiring shell (the F00 convention).
- Tests:
  - **Domain unit test** for `SameAccountTransferException` (accessor, message, `extends DomainException`).
  - **Domain unit test** for `TransferCommand` (null rejection, Money-positivity via constructor).
  - **Application unit test** `TransferFundsTest` (Mockito): covers the orchestration with mocked ports — happy path produces one save per aggregate plus one balanced journal entry; self-transfer throws `SameAccountTransferException` *without* touching the locker; missing source throws `ResourceNotFoundException` and never debits; missing destination throws `ResourceNotFoundException` and never debits; the `AccountLocker.withPairedLocks` is called with the original source and destination arguments (canonicalization is the locker's job, the use case does not pre-sort). Verifies argument ordering passed to the locker, verifies `JournalEntry` description names both accounts, verifies `Movement` types and amounts are correct.
  - **Persistence integration test** `bootstrap/src/test/.../persistence/transfer/TransferAtomicityIntegrationTest` (`@SpringBootTest`, real Spring context with real `JvmAccountLocker` and real JPA adapters): exercises happy path end-to-end (asserts both balances change, one new journal row, two new movement rows, journal status is `PENDING`); exercises atomicity by injecting a failure mid-flight (a `JournalEntries` spy that throws after the aggregates have been saved — asserts both account balances revert via transaction rollback and no journal entry is persisted).
  - **Controller integration test** `bootstrap/src/test/.../web/transfer/TransferControllerTest` (`@SpringBootTest`, `TestRestTemplate`): one test per spec scenario — `204` on success with subsequent `GET /api/v1/accounts/...` reflecting the new balances; `400 BAD_REQUEST_PAYLOAD` for missing source, missing destination, missing/zero/negative amount, and self-transfer; `404 RESOURCE_NOT_FOUND` for missing source and missing destination accounts; `400 ACCOUNT_INACTIVE` for a Suspended source and a Suspended destination; `400 INSUFFICIENT_FUNDS` for an overdraw attempt; assert journal-entry row count and balance state are unchanged for every rejection case.
  - **OpenAPI contract assertion** in the existing `OpenApiContractTest`: served document declares `createTransfer` operation under `/api/v1/transfers` and `TransferRequest` schema with the three required fields.

No edit to F01's `Account` aggregate. No edit to F02's `JournalEntries` port. No edit to F05's `Accounts` port. No new Gradle dependency. No new database migration.

## Capabilities

### New Capabilities
- `fund-transfer`: HTTP write endpoint `POST /api/v1/transfers` that moves a positive `Money` amount from one Active account to another Active account in a single atomic transaction. Composes F05's `Accounts` port, F02's `JournalEntries` port, and F07's `AccountLocker` into one use case (`TransferFunds`) that runs entirely inside the controller's `@Transactional` boundary. Produces exactly one balanced `JournalEntry` with status `PENDING` (F10 promotes to `VERIFIED` later); the entry's description names both accounts and its timestamp is the processing moment. Rejects self-transfers, missing-account references, inactive accounts, insufficient funds, and malformed payloads via the canonical F03 error envelope. Locks are acquired through F07's canonical-order primitive, so concurrent counter-direction transfers between the same pair never deadlock.

### Modified Capabilities
None. F06 is the first HTTP write endpoint and composes existing capabilities without changing their spec-level contracts:
- F03's `GlobalExceptionHandler` gains four new `@ExceptionHandler` methods. The F03 spec (`api-error-contract`) already requires the `INSUFFICIENT_FUNDS`, `ACCOUNT_INACTIVE`, and `BAD_REQUEST_PAYLOAD` mappings; F06 implements the handler entries for the domain types — no new requirement in the `api-error-contract` spec.
- F04's OpenAPI document grows by one path and one schema. F04's spec already requires "the contract is the source of truth and grows with each capability"; F06 grows it as described.
- F01, F02, F05, F07: consumed unchanged; no spec edit.

## Impact

- **Code**:
  - `domain/src/main/java/com/bank/core/domain/SameAccountTransferException.java` (new).
  - `application/src/main/java/com/bank/core/application/transfer/` — `TransferCommand.java`, `TransferFunds.java` (both new).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/web/transfer/` — `TransferController.java`, `TransferRequestMapper.java` (both new).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/web/error/GlobalExceptionHandler.java` (modified — four new handler methods, Javadoc trimmed).
  - `bootstrap/src/main/java/com/bank/core/BankCoreApplication.java` (modified — two new `@Bean` methods: `TransferFunds` and `Clock`).
- **OpenAPI**:
  - `bootstrap/src/main/resources/openapi/paths/transfers.yaml` (new).
  - `bootstrap/src/main/resources/openapi/schemas/transfer-request.yaml` (new).
  - `bootstrap/src/main/resources/openapi/openapi.yaml` (modified — register path, register schema, add `transfers` tag).
- **Schema**: none. F02's `journal_entry` + `ledger_movement` tables and F05's `account` table already cover everything F06 writes.
- **Build**: no new Gradle dependencies. The OpenAPI generator picks up the new path/schema automatically.
- **Conventions**:
  - Reaffirms F02's `transactional-in-application` precedent: the use case is plain Java, the `@Transactional` lives on the controller (the *one* place in the codebase where a controller is transactional — the wiring point for the multi-aggregate-write orchestration).
  - Reaffirms F00's "domain is JDK-only" — `SameAccountTransferException` extends `DomainException`, no Spring import.
  - Reaffirms F00's "application is Spring-free" — `TransferFunds` is plain Java, registered as a Spring bean via `@Bean` in bootstrap.
  - Reaffirms F00's "orchestration-shells-thin" — `TransferController.createTransfer(...)` is ≤5 lines: map DTO → command, call use case, return 204. All business logic in the use case.
- **Open decision closed**: `self-transfer` (manifest open question for F06) → resolution: reject with `400 BAD_REQUEST_PAYLOAD` via the new `SameAccountTransferException`. Allowing a no-op self-transfer would either create a self-debit/self-credit journal (semantically odd) or silently skip the journal (audit gap); rejecting is the safer default and consistent with most banking APIs.
- **Open decision unchanged**: `idempotency` (manifest) — explicitly out of scope. F06 ships without an `Idempotency-Key` header; retried POST requests will produce duplicate journal entries today. A future change introduces request-key handling once the operational need is clearer.
- **Open decisions consumed unchanged**: `lock-wait-timeout` (closed by F07 — F06 uses `bank.transfer.lock-wait-ms` transparently), `transactional-in-application` (closed by F02 — F06 honours it), `account-status-enum-coverage` (closed by F05 — F06 makes no schema additions), `debit-to-zero` (closed by F01 — F06 inherits the behaviour).
- **Downstream**:
  - **F08** (account opening) will call `TransferFunds.transfer(...)` from its account-opening use case to fund a freshly minted account from the clearing account, reusing F06's atomicity guarantee.
  - **F09** (dev data seeding) will use F08's account-opening flow, which transitively uses F06.
  - **F10** (journal verification) will sweep the `PENDING` journals F06 creates and promote them to `VERIFIED` after re-running the database-side balance check.
  - **F11** (balance drift detection) will reconcile the cached account balances F06 mutates against the per-account ledger sum from F02's movements.
- **Backwards compat**: zero. The endpoint is brand new; the OpenAPI document grows additively; no existing endpoint or schema is touched.
- **Operational notes**: The transactional boundary is the controller method. A crash between `accounts.save(source)` and `journals.save(entry)` rolls the entire transaction back — Spring's JPA transaction manager handles this transparently because all four writes (source UPDATE, destination UPDATE, journal INSERT, movement INSERTs) share the same JDBC connection within the `@Transactional` proxy. The F07 lock release fires on `afterCompletion`, so locks free up on both commit and rollback.
