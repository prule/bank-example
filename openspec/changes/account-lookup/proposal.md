## Why

F01 (`Account` aggregate, `AccountNumber`, `Money`, exception hierarchy) and F02 (immutable ledger schema + adapter) have shipped, F03 (error contract + global handler) and F04 (contract-first OpenAPI pipeline) provide the HTTP surface, and F07 (transfer-locking) is in place for the future write path. The codebase still has **no way to persist or read an account row** — `infrastructure/persistence/` holds only the ledger tables. Every downstream capability needs account persistence: F06 (fund transfer) needs to load source/destination aggregates inside the locked region; F08 (account opening) needs to insert new rows; F09 (dev data seeding) needs to upsert a clearing account and demo customers; F11 (balance drift detection) reads per-account ledger sums to compare against the cached balance.

F05 — a read-only `GET /api/v1/accounts/{accountNumber}` endpoint — is the smallest cut that **introduces the `account` table, the JPA entity, the application port, and the adapter** in one focused vertical slice. It also closes the `account-status-enum-coverage` open decision in the manifest by widening the OpenAPI status enum to include `CLOSED`, satisfying the spec's "Closed account is representable" scenario without bumping into stale-enum surprises later. F05's `ResourceNotFoundException` lands the 404 handler that F03 already prepared a slot for (see the `ResourceNotFoundException (F05) → 404 RESOURCE_NOT_FOUND` TODO in the handler's class-level Javadoc).

The manifest's `[F05, F06]` build slot sits after `[F07]` (just shipped). F05 ships first within that slot because it lays the account-persistence groundwork that F06 will consume; F06 then becomes a pure "use the F05 adapter + F07 locker + F02 journal adapter" stitch.

## What Changes

- Introduce the `Accounts` port in `application` as a plain Java interface in `com.bank.core.application.account` with two operations the lookup endpoint and downstream capabilities need:
  - `Optional<Account> findByNumber(AccountNumber number)` — returns the rehydrated aggregate or `Optional.empty()`; F05's lookup uses it.
  - `Account save(Account account)` — insert or update; F08/F09 will use it. F05 does not call it (read-only), but shipping the write side in the same change means F06 can compose F05+F02+F07 without a follow-up port edit. The save method is `void`-equivalent-but-returns-account so callers can rebind a freshly-rehydrated copy if they ever need a clean slate; in practice F05 will only use `findByNumber`.
- Introduce the `Account.rehydrate(AccountId id, AccountNumber number, Money balance, AccountStatus status)` package-public static factory on the F01 aggregate, parallel to F02's `JournalEntry.rehydrate(...)`. Class-level Javadoc on `Account` documents that application code MUST NOT call it; the mapper is the only legitimate caller. F01 already anticipated this in its `Account` Javadoc ("F02 will add a package-private constructor for persistence rehydration"); this change fulfils that note.
- Introduce JPA-backed persistence under `infrastructure/persistence/account/`:
  - `AccountEntity` — UUID PK (`id`), unique `account_number` VARCHAR(64), `balance` NUMERIC(19,2) with `CHECK (balance >= 0)`, `status` VARCHAR(16) with `CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))`. Field access, no public setters; package-private setters used only by the mapper.
  - `AccountRepository` — `Optional<AccountEntity> findByAccountNumber(String accountNumber)` plus standard `save`/`existsById`.
  - `AccountMapper` — `toEntity(Account)` and `toDomain(AccountEntity)` (the latter calls `Account.rehydrate(...)`).
  - `AccountsJpaAdapter` — `@Component` implementing the `Accounts` port; `@Transactional(readOnly = true)` on `findByNumber`, `@Transactional` on `save`.
- Add `bootstrap/src/main/resources/db/migration/V3__account.sql` creating the `account` table, the unique index on `account_number`, and the two CHECK constraints. The migration is portable across H2 vanilla (default profile) and H2 PostgreSQL-compat mode (test profile), continuing F00's two-profile discipline.
- Introduce the public domain exception `ResourceNotFoundException` in `com.bank.core.domain` extending `DomainException`. Carries the resource type (string, e.g. `"account"`) and the offending identifier (string) for log diagnostics. F05's controller throws it when a lookup misses; F03's handler maps it to HTTP 404 `RESOURCE_NOT_FOUND`. Extending the existing handler is now safe — F05 ships the exception type that the handler's prepared TODO references.
- Extend `GlobalExceptionHandler` in `infrastructure/web/error/` with one new `@ExceptionHandler(ResourceNotFoundException.class)` method mapping to HTTP 404 with `code = RESOURCE_NOT_FOUND` and a message that names the resource type and identifier (no leaking of internal IDs or stack traces). Update the class-level Javadoc to remove the now-fulfilled `ResourceNotFoundException (F05)` line from the "future capabilities" TODO list, and add an `INFO`-level log on each 404 lookup so operators can spot enumeration attacks.
- Extend the OpenAPI contract:
  - Add `bootstrap/src/main/resources/openapi/paths/accounts.yaml` with a single `GET` operation under operationId `lookupAccount`, path parameter `accountNumber` (string, non-blank), responses `200` (body = `AccountResponse`) and `404` (body = `ErrorEnvelope`).
  - Add `bootstrap/src/main/resources/openapi/schemas/account-response.yaml` with required fields `accountNumber` (string), `balance` (string, decimal with 2 fraction digits — JSON number would lose trailing zeros), and `status` (enum `ACTIVE | SUSPENDED | CLOSED`). Closing the `account-status-enum-coverage` open decision in favour of "enum includes CLOSED" satisfies the spec's "Closed account is representable" scenario explicitly.
  - Wire both refs into the root `openapi.yaml`: register a new path entry `/api/v1/accounts/{accountNumber}` referencing `paths/accounts.yaml`, and register `AccountResponse` under `components.schemas`. Add a new `accounts` tag with a one-line description.
- Implement `AccountController` in `infrastructure/web/account/` (`@RestController`) implementing the generated `AccountsApi` interface produced by the OpenAPI generator. The controller is a 4-line shell: inject the `Accounts` port, look up by number, throw `ResourceNotFoundException("account", number)` on `Optional.empty()`, map the loaded `Account` to `AccountResponse` via a thin mapper. No business logic in the controller; the F00 orchestration-shells-thin convention.
- Ship tests:
  - **Domain unit test** for `Account.rehydrate(...)` covering null-argument rejection (defence-in-depth on top of `Objects.requireNonNull`) and round-trip with each `AccountStatus` value.
  - **Domain unit test** for `ResourceNotFoundException` covering accessor round-trip, message formatting, and `extends DomainException`.
  - **Persistence integration test** in `bootstrap/src/test/.../persistence/account/AccountsJpaAdapterTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, `@Transactional`): `save` then `findByNumber` returns the same aggregate; `findByNumber` for a missing number returns `Optional.empty()`; the unique constraint on `account_number` rejects duplicate inserts.
  - **Controller integration test** in `bootstrap/src/test/.../web/account/AccountLookupControllerTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, `MockMvc`): existing account returns 200 with the documented body shape (assert exactly three fields); missing account returns 404 with `code = RESOURCE_NOT_FOUND` and a message naming the missing number; a `Closed` account is representable (status field serialises to `"CLOSED"`).
  - **OpenAPI contract test** — the existing `OpenApiContractTest` already validates that the served document matches the on-disk YAML; F05 adds an additional assertion that the served document contains the `lookupAccount` operation and the `AccountResponse` schema with the `CLOSED` enum entry, so future drift fails the build.
  - **Read-only-ness test** — count `journal_entry` rows before and after a `GET /api/v1/accounts/{number}` request; assert the count is unchanged. Same for `account` table rows (number does not change). This is the spec scenario "Lookup does not write to the ledger" made executable.

No edit to F07's `AccountLocker`. No new dependency in any Gradle module. No edit to F02's ledger schema or adapter.

## Capabilities

### New Capabilities
- `account-lookup`: Read-only HTTP endpoint `GET /api/v1/accounts/{accountNumber}` returning the current account number, balance (string with 2 decimals), and status (`ACTIVE | SUSPENDED | CLOSED`) as a single `AccountResponse` body, with a deterministic 404 (`RESOURCE_NOT_FOUND`) for missing accounts. Ships the foundational account persistence (the `account` table, `AccountEntity`, `AccountRepository`, `AccountMapper`, `AccountsJpaAdapter`) plus the `Accounts` application port consumed by F06/F08/F09 later. Lookup never mutates state — journal row count and account row state are unchanged across a request.

### Modified Capabilities
None. F05 introduces a new capability and adds two implementations that *extend* existing capabilities without changing their spec-level contracts:
- F03's `GlobalExceptionHandler` gains one new `@ExceptionHandler` method for `ResourceNotFoundException`. The F03 spec (`api-error-contract`) already requires the `RESOURCE_NOT_FOUND` mapping; F05 implements the handler entry — no new requirement is needed in the `api-error-contract` spec.
- F04's OpenAPI document gains a new path and a new schema. F04's spec (`contract-first-api`) already requires "the contract is the source of truth and grows with each capability"; F05 grows it as described. No new requirement is needed in the `contract-first-api` spec.

## Impact

- **Code**:
  - `domain/src/main/java/com/bank/core/domain/` — `ResourceNotFoundException.java` (new). `Account.java` (modified — adds package-private `rehydrate(...)` factory + Javadoc update; no behaviour change to existing four mutators).
  - `application/src/main/java/com/bank/core/application/account/Accounts.java` (new port).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/account/` — `AccountEntity.java`, `AccountRepository.java`, `AccountMapper.java`, `AccountsJpaAdapter.java` (new).
  - `infrastructure/src/main/java/com/bank/core/infrastructure/web/account/AccountController.java` (new) implementing the generated `AccountsApi`.
  - `infrastructure/src/main/java/com/bank/core/infrastructure/web/error/GlobalExceptionHandler.java` (modified — one new handler method + Javadoc update).
- **Schema**: `bootstrap/src/main/resources/db/migration/V3__account.sql` (new). Adds `account(id UUID PK, account_number VARCHAR(64) UNIQUE NOT NULL, balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0), status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')))` and `CREATE UNIQUE INDEX idx_account_account_number ON account(account_number)`. `ddl-auto=validate` confirms the entity matches.
- **OpenAPI**:
  - `bootstrap/src/main/resources/openapi/paths/accounts.yaml` (new).
  - `bootstrap/src/main/resources/openapi/schemas/account-response.yaml` (new).
  - `bootstrap/src/main/resources/openapi/openapi.yaml` (modified — register the path, register the schema, add the `accounts` tag).
- **Build**: no new Gradle dependencies. F02 already pulled in `spring-boot-starter-data-jpa`, H2, and Flyway. F04 already wired the OpenAPI generator; it picks up the new path and schema automatically.
- **Conventions**:
  - Reaffirms F00's "JPA entities live in `com.bank.core.infrastructure.persistence..`" (ArchUnit rule continues to pass).
  - Reaffirms F00's "application is Spring-free" — `Accounts` is a plain interface; the adapter has the `@Transactional` and `@Component` annotations.
  - Reaffirms F02's `transactional-in-application` precedent (transactions on the infrastructure adapter, not the port).
  - Reaffirms F00's "orchestration-shells-thin" — `AccountController` delegates to the port with no inline business logic.
- **Open decision closed**: `account-status-enum-coverage` (manifest open question for F05) → resolution: the OpenAPI `AccountResponse.status` enum includes `ACTIVE`, `SUSPENDED`, and `CLOSED`. A `Closed` account is therefore representable in the lookup response, matching the spec's scenario. Future status additions append to the enum.
- **Open decisions unchanged**: `idempotency` (F06 concern), `self-transfer` (F06 concern), `lock-wait-timeout` (closed by F07), `scheduler-config-externalised` (F10/F11 concern), `reactivation-playbook` (F11 concern), `transactional-in-application` (closed by F02), `debit-to-zero` (F01 concern, closed by F01's spec wording).
- **Downstream**:
  - **F06** (fund transfer) will inject the `Accounts` port and load source/destination aggregates *inside* `AccountLocker.withPairedLocks(...)`. The two-aggregate `save` calls plus the `JournalEntries.save(...)` call all run inside the single transactional boundary the controller starts via `@Transactional`. F06 does not need any further port edit on F05's side.
  - **F08** (account opening) will call `Accounts.save(...)` for a freshly minted aggregate produced by `Account.open(...)`, then trigger F06's transfer pipeline to fund it from the clearing account in the same transactional boundary.
  - **F09** (dev data seeding) will call `Accounts.save(...)` from a startup `ApplicationRunner` gated by `SEED_DATA=true`, upserting a clearing account and a handful of demo customers; idempotency is enforced by the unique index on `account_number`.
  - **F10** (journal verification) is independent of F05's surface — it does not need to read accounts, only journals. No change.
  - **F11** (balance drift detection) will need both the `Accounts` port (to load the aggregate and compare its cached balance against the ledger sum) and a new per-account ledger-sum query (likely added to `JournalEntries` in F11 itself, or as a separate `LedgerQueries` port — out of scope here). F05 ships the part F11 needs from this side.
- **Backwards compat**: zero. The `account` table does not yet exist; the new endpoint introduces a fresh URL; the OpenAPI document grows additively. Existing endpoints (`/v3/api-docs`, `/actuator/health`) are untouched.
- **Operational notes**: `GET /api/v1/accounts/{accountNumber}` is unauthenticated for now (consistent with the existing surface). Operators monitoring for enumeration attacks can use the new `INFO`-level log line from the 404 handler. Production lookups are read-only and bounded by the unique-index lookup cost; no caching is added in F05.
