## Why

The `domain` module now has `Account`/`Money`/`AccountId` from F01, but nothing yet records *where money went*. Every other capability that touches money depends on the ledger: F06 (fund transfer) writes one balanced journal entry per transfer, F08 (account opening) seeds new accounts via a transfer from the clearing account, F10 (journal verification) sweeps `Pending` journals and promotes them to `Verified`/`Failed`, F11 (balance drift detection) walks the ledger to reconcile cached balances. The published REQUIREMENTS document calls the ledger "the source of truth" — what an account *actually* holds is derivable from the ledger; the cached balance is a fast view. F02 lays down the persistent, append-only data shape that this contract demands, including the indexes and the database-side balance check that F10/F11 will rely on.

F02 is named alongside F01 in the manifest's `[F01, F02]` build slot because both are pure-domain-plus-persistence concerns and can ship independently. F01 has shipped; F02 ships now so F06 can land next.

## What Changes

- Introduce the pure-domain `JournalEntry` aggregate under `com.bank.core.domain`: identity (`JournalEntryId` UUID record), description, timestamp (`Instant`), verification status (`VerificationStatus` enum: `PENDING`, `VERIFIED`, `FAILED`), and an immutable ordered list of `Movement` legs. The factory `JournalEntry.create(description, timestamp, movements)` enforces at-least-two-movements and matched-sum invariants at construction. Status mutates only via `markVerified()` and `markFailed()`; both throw `IllegalStatusTransitionException` if the journal is not `PENDING`.
- Introduce `Movement` as a pure-domain value object: `AccountId accountId`, `Money amount` (always positive), `MovementType type` (`DEBIT` or `CREDIT`). The constructor refuses non-positive amounts. `Movement` has no id — the database assigns and owns monotonic ids; the domain treats movements as value-typed legs of a journal entry.
- Introduce the `JournalEntries` port in `application` (a plain Java interface, no Spring) with the four operations downstream capabilities need: `save(JournalEntry)` (insert, returns void since the domain owns the id), `findById(JournalEntryId)` (returns `Optional<JournalEntry>`), `findByStatus(VerificationStatus, int limit)` (returns `List<JournalEntry>` for sweeper paging), and `isBalanced(JournalEntryId)` (single-aggregate DB-side check returning `boolean`).
- Introduce JPA-backed adapters in `infrastructure.persistence.ledger`: `JournalEntryEntity` (UUID PK, varchar status, ISO timestamp), `LedgerMovementEntity` (Long `IDENTITY` PK for monotonic ordering, FK to journal, account id, NUMERIC(19,2) amount with positive check, varchar movement_type). A `JournalEntriesJpaAdapter` implements the application port. JPA mapping uses field access, not records, because JPA needs no-arg constructors.
- Add a Flyway migration `V2__ledger.sql` under `bootstrap/src/main/resources/db/migration/` that creates `journal_entry`, `ledger_movement`, the FK, the positive-amount CHECK, and three indexes: `ledger_movement(account_id)`, `ledger_movement(journal_entry_id)`, `journal_entry(verification_status)`. The migration is portable across H2's vanilla mode (default profile) and H2 PostgreSQL-compatibility mode (test profile), so F00's existing two-profile schema discipline keeps working.
- Add a database-side balance query: `SELECT COALESCE(SUM(CASE WHEN movement_type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE journal_entry_id = ?`. Returns zero iff the journal balances. The adapter's `isBalanced(...)` runs this query and compares to `BigDecimal.ZERO` with scale-aware comparison.
- Tests:
  - **Domain unit tests** for `JournalEntry` (forward-only status transitions, sum-balance enforcement at construction, immutable movement list), `Movement` (rejects non-positive amount, rejects null fields), enum coverage.
  - **Integration tests** (`@DataJpaTest` plus Flyway-managed schema) for `JournalEntriesJpaAdapter` covering: persist round-trip, status transition persistence, paged-by-status query ordering, `isBalanced` for balanced/unbalanced journals, monotonic-movement-id assertion (insert two journals in sequence, observe id of the second journal's movements is strictly greater than the first).
  - **ArchUnit assertion** that `JournalEntryEntity` and `LedgerMovementEntity` live in `com.bank.core.infrastructure.persistence..` (F00's existing rule already covers this; F02 verifies it stays green).
- Close the open decision `transactional-in-application` from [openspec/config.yaml](openspec/config.yaml) in favour of "no Spring in application." F02 places `@Transactional` on the infrastructure adapter, not on use cases. The port stays a plain interface. The decision becomes precedent for F06/F08/F10/F11.

No public endpoint ships. F02 lays the data shape and the adapter; F06 writes into it next; F10/F11 consume it for audit.

## Capabilities

### New Capabilities
- `immutable-ledger`: Append-only double-entry journal that is the source of truth for every cent that has moved. A `JournalEntry` carries a UUID id, description, timestamp, mutable-only-via-named-mutators verification status (`PENDING → VERIFIED|FAILED`), and an immutable list of `Movement` legs. `Movement` records account, positive amount, and `DEBIT`/`CREDIT` direction. Movement ids are database-assigned (`IDENTITY`) and globally monotonic across all journals, so background auditing can use them as a checkpoint cursor. A single-aggregate database query checks whether a persisted journal balances. The application port `JournalEntries` exposes save / find-by-id / paged-find-by-status / is-balanced for downstream capabilities.

### Modified Capabilities
None. F02 is the first capability to touch the schema beyond F00's placeholder `V1__init.sql`. Other capabilities (F06/F08/F10/F11) will write *into* the ledger but do not modify the F02 spec; they reference it.

## Impact

- **Code**: Adds `JournalEntry.java`, `JournalEntryId.java`, `Movement.java`, `MovementType.java`, `VerificationStatus.java` under `domain/src/main/java/com/bank/core/domain/`. Adds `JournalEntries.java` (port interface) under `application/src/main/java/com/bank/core/application/ledger/`. Adds `JournalEntryEntity.java`, `LedgerMovementEntity.java`, `JournalEntryRepository.java` (Spring Data JPA), `JournalEntriesJpaAdapter.java`, and a mapper class under `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/ledger/`.
- **Schema**: Adds `bootstrap/src/main/resources/db/migration/V2__ledger.sql` creating `journal_entry`, `ledger_movement`, foreign key, positive-amount CHECK, three indexes.
- **Build**: No new Gradle dependencies. F00 already supplies `spring-boot-starter-data-jpa`, Flyway, H2 in `infrastructure`/`bootstrap`. JUnit 5 + Spring Boot test from F00 covers the `@DataJpaTest` and `@SpringBootTest` setups.
- **Conventions**:
  - Reaffirms F00's "JPA entities live in `com.bank.core.infrastructure.persistence..`" rule (ArchUnit boundary test continues to pass).
  - Reaffirms F00's "ddl-auto=validate" rule (the new entities must match the Flyway schema or boot fails — this is the whole point).
  - Establishes the pattern (closing the `transactional-in-application` open decision): ports in `application` are plain interfaces, `@Transactional` annotations live on infrastructure adapter classes.
- **Downstream**:
  - **F06** (fund transfer) will call `JournalEntries.save(...)` after `source.debit(amount)` + `destination.credit(amount)` inside one transactional boundary. The transfer use-case lives in `application`; the adapter's `@Transactional` boundary handles JPA flushing.
  - **F08** (account opening) will call `JournalEntries.save(...)` for the funding entry that moves the opening balance from the clearing account.
  - **F10** (journal verification) will call `JournalEntries.findByStatus(PENDING, n)` on its schedule, then `isBalanced(...)` per journal, then `markVerified()`/`markFailed()` and re-save. Account suspension on `FAILED` happens through `Account.suspend()` (F01) inside the same use case.
  - **F11** (balance drift detection) will need additional ledger queries (per-account sum up to a movement-id ceiling). F02 ships the indexes that make those queries efficient; the query methods themselves can land in F11 since they are F11-specific.
- **Open decision closed**: `transactional-in-application` → resolution: `application` stays Spring-free; `@Transactional` lives on infrastructure adapter classes. F02 implements this and sets the precedent.
- **Open decision unchanged**: `idempotency` (retried transfer requests would today duplicate journals) remains open. F02 deliberately does not introduce an idempotency-key column on `journal_entry` — that is a v2 enhancement that needs an external request-key concept (`Idempotency-Key` header or similar). F02 leaves room: the journal id is UUID-typed today, so an idempotency key can be added later as a separate UNIQUE column without rewriting the journal id.
- **No public API surface**: F02 ships no endpoint. The contract added in F04 is unchanged; the OpenAPI document does not need an edit. Service still serves `/v3/api-docs` and `/actuator/health` as before.
- **Backwards compat**: zero — nothing currently exists in `journal_entry` or `ledger_movement` (the tables don't yet exist). F02 introduces them at version `V2`.
