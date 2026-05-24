## Context

F00 set up Gradle (4 modules, Flyway-managed schema, `ddl-auto=validate`), F04 wired the OpenAPI pipeline, F03 wired the global error handler, F01 introduced the `Account`/`Money` domain. The persistence ground is bare: `bootstrap/src/main/resources/db/migration/V1__init.sql` is a single-comment placeholder, there is no JPA entity anywhere in the codebase, and `infrastructure/src/main/java/com/bank/core/infrastructure/persistence/` contains only a `.gitkeep`. F02 is the first capability to define a schema that actually holds data.

The published spec at [openspec/specs/immutable-ledger/spec.md](openspec/specs/immutable-ledger/spec.md) commits to six requirements: journal-entry shape (UUID + status PENDING/VERIFIED/FAILED + ordered movements + forward-only status transitions), movement immutability (positive amount, debit/credit), append-only journals, globally monotonic movement ids, database-side balance check, paged status query. The REQUIREMENTS doc names the ledger "the source of truth" — what an account holds is derivable from the ledger; the cached balance on `Account` is a fast view. F11 will exploit that derivability.

Constraints inherited from F00:
- `domain` is JDK-only — `JournalEntry`/`Movement`/the enums are plain Java, no JPA, no Spring, no Jackson, no openapi-generated DTOs.
- `application` may not import Spring (production sources) — the `JournalEntries` port is a plain interface.
- `infrastructure` is allowed Spring/JPA — JPA entities, Spring Data repositories, and the adapter that implements the port live here.
- JPA entities MUST reside under `com.bank.core.infrastructure.persistence..` (F00 ArchUnit rule #4).
- Flyway owns the schema; `ddl-auto=validate` enforces match — entities and the migration must agree.
- Test profile uses H2 PostgreSQL-compatibility mode; default profile uses H2 vanilla. The migration must work in both.

Open decision touched: `transactional-in-application` from [openspec/config.yaml](openspec/config.yaml). F02 closes it in favour of "no Spring in `application`." The transactional boundary lives on the adapter in `infrastructure`.

## Goals / Non-Goals

**Goals:**
- `JournalEntry` enforces every invariant the spec names *at construction or at the named mutator*. The aggregate cannot be in an invalid state once it exists: at least two movements, sum of credits = sum of debits, all movements have positive amounts, status starts `PENDING`, status only moves `PENDING → VERIFIED|FAILED` via named mutators.
- `Movement` is a pure value object — equality on all fields — with constructor-level validation of positive amount.
- The `JournalEntries` port has the four minimum operations downstream caps need (`save`, `findById`, `findByStatus(status, limit)`, `isBalanced(id)`). No leakage of Spring/JPA types in its signatures.
- The adapter's `isBalanced(id)` runs as one aggregate SQL query (`SUM(CASE ...)`), confirmed by a SQL-capture test using H2's session log or by manual review.
- Movement ids are `BIGINT` `IDENTITY` columns, monotonically assigned by H2. An integration test inserts two journals in sequence and asserts the second's movement ids are strictly greater than the first's.
- Inserting an unbalanced journal entry through the application port is rejected by the domain — the schema cannot contain unbalanced journals via that path. (A future direct-SQL insert that bypasses the domain could create one; F10's verification sweeper catches that.)
- Persisted journals are append-only in practice: no JPA update query mutates movements; the only `@Modifying` query is the status transition on `journal_entry`. Spring Data repositories generate no setters callable from the application layer — the JPA entity's setters are package-private and only the mapper uses them.
- F00 ArchUnit boundary rules continue to pass.

**Non-Goals:**
- Idempotency key on journal entries. Open decision in the manifest; defer to a separate change. F02 leaves room (journal id is UUID, separate from any future external request key).
- Per-account aggregate queries used by F11 (running balance up to a ceiling). F11 will add the query method; F02 just ships the indexes that make it cheap.
- Compensating-entry workflow. The spec calls out that future spec might add corrections; F02 does not foreclose by making movements append-only and journals append-only-except-status.
- Multi-currency. Movement amount is `BigDecimal`-typed at scale 2, currency-less per the REQUIREMENTS doc.
- Read models / projections / event sourcing. The ledger is the system of record; balances are read directly from the ledger when needed (F11 query). No CQRS read store.
- Encryption at rest, soft delete, audit table — beyond the iteration scope.
- Closed-loop "post the transfer to two account aggregates" workflow. F06 owns that orchestration; F02 just receives the resulting journal.

## Decisions

### Domain layout

Under `com.bank.core.domain` (flat — F01 set the precedent):

```
com.bank.core.domain/
├── Account.java                       (F01)
├── AccountId.java                     (F01)
├── AccountNumber.java                 (F01)
├── AccountStatus.java                 (F01)
├── Money.java                         (F01)
├── DomainException.java               (F01)
├── … existing F01 exceptions …
├── JournalEntry.java                  (F02 — new)
├── JournalEntryId.java                (F02 — new)
├── Movement.java                      (F02 — new)
├── MovementType.java                  (F02 — new)
├── VerificationStatus.java            (F02 — new)
└── UnbalancedJournalException.java    (F02 — new domain exception)
```

`JournalEntry` is a `public final class` with `private` constructor + `static create(...)` factory. The factory takes (description, timestamp, movements list), validates the invariants, returns a new aggregate with status `PENDING`. `id` is generated inside the factory (`JournalEntryId.generate()` analogous to `AccountId.generate()`).

`Movement` is a `public record Movement(AccountId accountId, Money amount, MovementType type)` with compact-constructor validation: all three non-null; amount must be positive (`!amount.isZero()` — `Money` already refuses negative).

`MovementType` is an enum: `DEBIT`, `CREDIT`.

`VerificationStatus` is an enum: `PENDING`, `VERIFIED`, `FAILED`. Each declares a `canTransitionTo(VerificationStatus next)` helper for clarity; only `PENDING → VERIFIED` and `PENDING → FAILED` return true.

`UnbalancedJournalException` extends F01's `DomainException`. Thrown by the factory when the input movements don't sum to zero. Carries the offending journal description plus the credit-sum and debit-sum for log readability.

`IllegalStatusTransitionException` from F01 is reused by `markVerified`/`markFailed` when called on a non-`PENDING` journal. (F01's exception was originally introduced for `Account` status transitions; reusing it here is correct — same invariant, same exception type.)

Rejected: a separate `LedgerEntryException` per problem. F01 established the practice of one exception type per business rule violation; reusing the same `IllegalStatusTransitionException` for the same shape of violation is consistent. `UnbalancedJournalException` is new because there is no analogous F01 concept.

### Why `Movement` is a `record` but `Account`/`JournalEntry` are classes

Per F01's design: records are appropriate for immutable value objects; classes with `private` mutable fields and named mutators are appropriate for aggregates whose state changes. `Movement` never changes (the spec says so explicitly: "Once a movement is persisted, its account reference, amount, and type SHALL NOT be mutable"). `JournalEntry`'s status changes (`PENDING → VERIFIED`), so it cannot be a record.

### Application port

`application/src/main/java/com/bank/core/application/ledger/JournalEntries.java`:

```java
public interface JournalEntries {
    void save(JournalEntry entry);
    Optional<JournalEntry> findById(JournalEntryId id);
    List<JournalEntry> findByStatus(VerificationStatus status, int limit);
    boolean isBalanced(JournalEntryId id);
}
```

No Spring annotations. Plural-noun name (`JournalEntries`) per the project's port-naming convention (no `Repository` suffix; ports describe the *collection*, not the implementation pattern).

`save(JournalEntry)` is `void` because the domain owns the id (generated in the factory). Callers do not need a return.

`findByStatus(status, limit)` returns a `List<JournalEntry>`. Ordering: by `journal_entry.entry_timestamp ASC, id ASC` for deterministic paging. Limit is enforced by the adapter via `LIMIT ?` (H2/PostgreSQL).

`isBalanced(id)` returns `false` for a non-existent journal (operational consequence: a verifier asking about a stale id treats it as not-balanced, which is the safer default).

Rejected: returning paging metadata (`Page<JournalEntry>` Spring Data type) — that bleeds Spring into application. `List<JournalEntry>` + `limit` is enough for F10's sweeper.

### JPA entity layout under `com.bank.core.infrastructure.persistence.ledger`

```
infrastructure/src/main/java/com/bank/core/infrastructure/persistence/ledger/
├── JournalEntryEntity.java            (@Entity, table "journal_entry")
├── LedgerMovementEntity.java          (@Entity, table "ledger_movement")
├── JournalEntryRepository.java        (Spring Data JPA, internal, package-private)
├── LedgerMovementRepository.java      (Spring Data JPA, internal, package-private)
├── JournalEntryMapper.java            (entity ↔ domain mapping)
└── JournalEntriesJpaAdapter.java      (@Component, @Transactional, implements JournalEntries)
```

`JournalEntryEntity`:
- `@Id` UUID `id` (no `@GeneratedValue` — domain provides).
- `String description` (length 255).
- `Instant timestamp` (column `entry_timestamp` — `timestamp` is a reserved word in some dialects).
- `@Enumerated(EnumType.STRING) VerificationStatus verificationStatus`.
- `@OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST, orphanRemoval = false) @OrderColumn(name = "movement_order") List<LedgerMovementEntity> movements`.
- Package-private setters; no public mutators.

`LedgerMovementEntity`:
- `@Id @GeneratedValue(strategy = IDENTITY) Long id`.
- `@ManyToOne(fetch = LAZY) @JoinColumn(name = "journal_entry_id", nullable = false) JournalEntryEntity journalEntry`.
- `@Column(name = "account_id", nullable = false) UUID accountId`.
- `@Column(precision = 19, scale = 2, nullable = false) BigDecimal amount`.
- `@Enumerated(EnumType.STRING) MovementType movementType` (column `movement_type`).

JPA needs a no-arg constructor and field access; both entities have package-private no-arg constructors and package-private getters/setters. Production code outside this package goes through `JournalEntriesJpaAdapter` and `JournalEntryMapper`.

Rejected: `@Embeddable` for movements. Embeddable collections in Hibernate use a separate table anyway and have rougher behaviour around updates; a proper `@OneToMany` with a dedicated entity is cleaner.

### Why field access, not method access, for JPA

Hibernate defaults to property access when `@Id` is on a getter, field access when `@Id` is on the field. Field access is preferred here so the entity's public surface (getters) does not need to exist solely to satisfy JPA. The mapper uses package-private getters internally.

### Mapper

`JournalEntryMapper`:

```java
static JournalEntryEntity toEntity(JournalEntry domain) { … }
static JournalEntry toDomain(JournalEntryEntity entity) { … }
```

The mapper sits next to the entities. `toEntity` creates a fresh entity tree from the domain object, including new `LedgerMovementEntity` instances per `Movement`. `toDomain` reads the entity tree and reconstructs a `JournalEntry` — but `JournalEntry` only has a public factory `create(...)` that re-validates invariants. Re-validating on every read is wasteful, so a *package-private* `JournalEntry.rehydrate(...)` constructor exists for the mapper to use. This is exactly the F01 forward-compat note (`F02 will add a package-private constructor for persistence rehydration`).

The same `rehydrate` is added to `Account` in this change too, since F06's transfer flow will need to load and re-save accounts and the same pattern applies. Even if F06 isn't in F02's scope, it's the symmetric thing — adding `Account.rehydrate` now (and keeping it unused for this change beyond a smoke test) avoids a future drive-by edit.

Actually — scope discipline: F02 only adds `JournalEntry.rehydrate`. `Account.rehydrate` waits for F06 / the account repository to land. Skip the pre-add.

### `isBalanced` query

```java
@Query("""
       SELECT COALESCE(SUM(CASE WHEN m.movementType = com.bank.core.domain.MovementType.CREDIT
                                THEN m.amount ELSE -m.amount END), 0)
       FROM LedgerMovementEntity m
       WHERE m.journalEntry.id = :journalId
       """)
BigDecimal sumSignedAmount(@Param("journalId") UUID journalId);
```

The adapter calls this and asserts `result.signum() == 0` (scale-independent). One round-trip, no in-memory iteration. The query is a JPQL aggregate, which Hibernate compiles to a single `SELECT SUM(...)`.

Alternatives considered:
- **Native SQL via `EntityManager.createNativeQuery`**: cleaner SQL but loses JPQL's enum-comparison portability. JPQL works on both H2 modes; the `CASE WHEN` form is portable.
- **Aggregating in Java after `findAll`**: violates the spec — F10/F11 will call this thousands of times; one aggregate query is mandatory.

### Monotonic movement id strategy

H2's `IDENTITY` column maps to a per-table sequence. Inserts within a single statement get consecutive ids; concurrent inserts get non-consecutive but strictly increasing ids per session. Across sessions, the sequence guarantees uniqueness and monotonicity in *commit order* of `nextval` calls, but *commit visibility* can interleave (transaction A reserves id 5, transaction B reserves id 6, B commits first, A commits second). For F11's drift detector this is the textbook "captured ceiling" race the spec already accounts for.

For F02 specifically, the test asserts: insert journal-A with two movements, *commit*; insert journal-B with two movements, *commit*; assert all movements of B have id > all movements of A. This holds because each `INSERT` reserves an `IDENTITY` value at insert time, and within a single transaction the ids are sequential.

Rejected alternatives:
- **Application-side sequence** (Snowflake, UUIDv7): adds external dependency or complexity; database `IDENTITY` is sufficient for the spec's "globally monotonic" wording given F11's captured-ceiling workaround.
- **Compound key** (journal_id, movement_order): solves ordering within a journal but not across journals. F11 needs a global cursor.

### Flyway migration `V2__ledger.sql`

```sql
CREATE TABLE journal_entry (
    id UUID NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    entry_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    verification_status VARCHAR(16) NOT NULL,
    CONSTRAINT verification_status_valid
        CHECK (verification_status IN ('PENDING', 'VERIFIED', 'FAILED'))
);

CREATE TABLE ledger_movement (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    journal_entry_id UUID NOT NULL,
    movement_order INTEGER NOT NULL,
    account_id UUID NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    movement_type VARCHAR(8) NOT NULL,
    CONSTRAINT fk_ledger_movement_journal FOREIGN KEY (journal_entry_id)
        REFERENCES journal_entry(id),
    CONSTRAINT amount_positive CHECK (amount > 0),
    CONSTRAINT movement_type_valid CHECK (movement_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_movement_account_id ON ledger_movement(account_id);
CREATE INDEX idx_ledger_movement_journal_id ON ledger_movement(journal_entry_id);
CREATE INDEX idx_journal_entry_status ON journal_entry(verification_status);
```

- `BIGINT GENERATED BY DEFAULT AS IDENTITY` is SQL:2003 standard, works on H2 vanilla and H2 PostgreSQL-compat mode. The `BY DEFAULT` form (not `ALWAYS`) lets Hibernate's `IDENTITY` strategy supply the value if it wanted — it won't, but `BY DEFAULT` keeps the column friendlier.
- `TIMESTAMP WITH TIME ZONE` is supported by both H2 modes for `Instant` mapping. Hibernate maps `Instant` to `TIMESTAMP WITH TIME ZONE` since Spring Boot 3.x.
- The enum columns are stored as strings with a `CHECK` constraint mirroring the Java enum. This is defence-in-depth: even if a future migration adds a new status, the DB schema flags any orphan value.
- `movement_order` records the position within the journal so `@OrderColumn` works.
- All three indexes are needed: account_id for F11 per-account scan, journal_entry_id for `isBalanced`, status for the F10 sweeper.

Rejected: `journal_entry.entry_timestamp DEFAULT NOW()`. The domain supplies the timestamp explicitly (it's part of the factory signature). Defaults would mask bugs where callers forget to pass a timestamp.

### `@Transactional` placement (closes `transactional-in-application` open decision)

`JournalEntriesJpaAdapter` is annotated `@Component` and each public method that mutates state carries `@Transactional`. The `application` module remains Spring-free. The use cases (F06's transfer use case, F10's verification use case) call port methods; the *port implementation* is what carries the transactional boundary. If a use case needs a transaction across multiple port calls, F06 will introduce a thin `@Service` orchestrator in `infrastructure` that calls the use case inside a `@Transactional` — F02 sets this precedent without locking the F06 design.

Recorded in tasks.md as the closing rationale for `transactional-in-application`.

### Ordering and `@OrderColumn`

`@OneToMany ... @OrderColumn(name = "movement_order")` keeps the movements in insertion order so `JournalEntry.movements()` returns them in the order the domain factory received them. JPA materialises `movement_order = 0, 1, 2, ...` per journal.

Without `@OrderColumn`, the load order would be implementation-defined and tests could flake.

### Test plan summary (full version in tasks.md)

- Domain tests in `domain/src/test/java/com/bank/core/domain/`:
  - `JournalEntryTest`: factory requires ≥2 movements, sums must match (else `UnbalancedJournalException`), starts `PENDING`, `markVerified` only from `PENDING`, `markFailed` only from `PENDING`, double-call throws `IllegalStatusTransitionException`, movements list is unmodifiable.
  - `MovementTest`: non-positive amount rejected (zero), null fields rejected.
  - `VerificationStatusTest`: `canTransitionTo` truth table.
- Persistence tests in `infrastructure/src/test/java/com/bank/core/infrastructure/persistence/ledger/`:
  - `JournalEntriesJpaAdapterTest` annotated `@DataJpaTest` (uses Flyway in test profile per F00 setup). Round-trip, status transitions persist correctly, paged query returns ordered results, `isBalanced` returns true for balanced and false for unbalanced (insert unbalanced via raw EntityManager bypassing the domain to test the SQL).
  - Monotonic-id test: two journals each with two movements; assert `journal-B.movements[0].id > journal-A.movements[1].id`.
- ArchUnit: F00's `ModuleBoundaryTest.jpaEntitiesLiveInInfrastructurePersistence` continues to pass — the new entities reside in `com.bank.core.infrastructure.persistence.ledger`.

## Risks / Trade-offs

- **H2 `IDENTITY` reservation vs commit order race** → flagged in the monotonic-id discussion; F11 accounts for it via captured-ceiling. Mitigation: documented; not F02's problem.
- **`@OrderColumn` performance** → maintaining an order column requires UPDATE on insertions in the middle. Since the ledger is append-only and the spec forbids middle-of-list edits, this never happens. New movements append to the end; insertion is O(1).
- **Flyway portability across H2 modes** → both H2 vanilla and H2 PostgreSQL-compat mode accept SQL:2003-style `GENERATED BY DEFAULT AS IDENTITY`. Spot-checked the syntax in H2 docs; the verification step in `tasks.md` runs `./gradlew :bootstrap:test` (test profile) and `./gradlew :bootstrap:bootRun` (default profile) to prove both apply cleanly.
- **`ddl-auto=validate` is strict about column types** → mapping `Instant` to `TIMESTAMP WITH TIME ZONE` works on H2 (`org.hibernate.dialect.H2Dialect`). If a future migration to PostgreSQL changes the dialect, the column type may differ (`TIMESTAMPTZ` vs `TIMESTAMP WITH TIME ZONE`) — listed as a future concern, not F02 blocker.
- **Movement list mutation through the getter** → `JournalEntry.movements()` returns `Collections.unmodifiableList(...)` over the internal list. The domain test asserts a mutation attempt throws. JPA's loaded list is a `PersistentList` — the mapper copies to a regular `ArrayList` then wraps unmodifiable.
- **Bypassing the domain to insert an unbalanced journal** → the SQL CHECK on amount prevents non-positive movements but doesn't enforce sum balance (that's per-journal not per-row, hard to express as a CHECK). F10's sweeper exists precisely to catch this case. The unbalanced-insert test uses raw `EntityManager` to validate F10's premise that unbalanced rows can exist; F10 will catch and mark them `FAILED`.
- **JPA dirty checking on `VerificationStatus`** → status is the only mutable column. Spring Data's default dirty checking writes the whole row even if only one column changed; for journals with many movements this is inefficient. Mitigation: a `@Modifying @Query` for status-only updates can be added in F10 if it shows up as a perf problem. Not in F02.

## Migration Plan

- **Deploy**: PR lands. `./gradlew clean build` runs Flyway against H2 in test/default profiles, applies V2, validates the entity mappings, runs all tests. `:bootstrap:bootRun` then `curl /actuator/health` returns 200 UP with `db: UP` confirming Flyway succeeded on startup. F02 ships no endpoint, so no further runtime probe is meaningful.
- **Rollback**: All additions plus one new migration. `git revert` reverses the code; `flyway:undo` is not available on the OSS edition — for a dev/test H2 in-memory database, restart wipes state. Production rollback (not in scope on this branch) would need a `V3__rollback_ledger.sql` migration that `DROP`s the new tables.
- **Forward path**: F06 calls `JournalEntries.save` inside its transfer use case; F08 calls it in account-opening; F10 sweeps `findByStatus(PENDING, n)` and calls `isBalanced`/`markVerified`/`markFailed`; F11 adds an account-balance-sum query alongside this port.

## Open Questions

- **Should `JournalEntries.save` reject already-`VERIFIED`/`FAILED` journals?** Today it does not — a caller could `save(...)` a journal that was already marked terminal. Practical risk: zero (no caller has reason to do this in F06–F11). Decision: trust the call site; the spec only forbids *transitions* away from terminal states, not duplicate-saves of terminal-state aggregates.
- **Should `findByStatus` accept a "since timestamp" filter?** F10's sweeper could benefit from `findByStatus(PENDING, sinceTimestamp, n)` to avoid re-reading old rows on every tick. Defer to F10 — F02 ships the simpler version; F10 decides whether to evolve it.
- **`description` field length 255** → arbitrary; could be `TEXT`. 255 chosen for cheap indexing if needed later. Defer to first stakeholder complaint.
- **Timestamp source** → domain factory takes an `Instant`. F06/F08/F10 will pass `Instant.now()`; tests pass fixed instants. A `Clock` port would let tests freeze time, but F02 doesn't need it since the factory accepts any instant the caller provides.
- **`amount > 0` CHECK redundancy** → enforced by both `Money` (in domain) and the SQL CHECK. Two layers of defence; the CHECK catches direct-SQL bypass. Keep both.
