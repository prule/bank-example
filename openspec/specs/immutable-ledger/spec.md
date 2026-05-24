# Immutable Ledger

## Purpose

Define the append-only accounting record. Every movement of money in the system produces ledger entries that are never modified once written. The ledger — not the cached account balance — is the source of truth for what an account holds.

## Requirements

### Requirement: Journal entry shape and lifecycle

A journal entry SHALL carry a stable identifier (`JournalEntryId` UUID), a description, a timestamp (`Instant`), a verification status from the set `PENDING`, `VERIFIED`, `FAILED`, and an ordered immutable list of movements. A journal entry SHALL begin life as `PENDING`. The verification status SHALL transition only `PENDING → VERIFIED` or `PENDING → FAILED` via the named mutators `markVerified()` and `markFailed()`; any other transition (including back to `PENDING`) SHALL throw `IllegalJournalStatusTransitionException`.

#### Scenario: New journal starts PENDING

- **WHEN** a journal entry is created via `JournalEntry.create(description, timestamp, movements)` with two or more movements that sum to zero
- **THEN** its status is `PENDING`, its id is a fresh UUID, and it carries the supplied description, timestamp, and movements in insertion order

#### Scenario: Factory rejects unbalanced movements

- **WHEN** `JournalEntry.create(...)` is called with movements whose credit sum does not equal debit sum
- **THEN** the call throws `UnbalancedJournalException` carrying the credit-sum and debit-sum for log diagnostics

#### Scenario: Factory rejects fewer than two movements

- **WHEN** `JournalEntry.create(...)` is called with zero or one movements
- **THEN** the call throws `IllegalArgumentException` because a balanced entry requires at least one credit and one debit leg

#### Scenario: markVerified only from PENDING

- **WHEN** `markVerified()` is called on a `VERIFIED` or `FAILED` journal
- **THEN** the call throws `IllegalJournalStatusTransitionException` and the status is unchanged

#### Scenario: markFailed only from PENDING

- **WHEN** `markFailed()` is called on a `VERIFIED` or `FAILED` journal
- **THEN** the call throws `IllegalJournalStatusTransitionException` and the status is unchanged

### Requirement: Movements are immutable accounting legs

A movement SHALL record the affected `AccountId`, a strictly positive `Money` amount, and a `MovementType` (`DEBIT` for money leaving the account, `CREDIT` for money entering it). Once a `Movement` value is constructed, its account reference, amount, and type SHALL NOT be mutable — `Movement` is a `record` and the persistence adapter holds an `@OneToMany` of entities whose corresponding column setters are package-private and called only by the mapper during initial persistence.

#### Scenario: Movement with non-positive amount is rejected at construction

- **WHEN** a `Movement` is constructed with an amount of `Money.ZERO`
- **THEN** construction throws `InvalidAmountException` and no `Movement` instance exists

#### Scenario: Movement with null field is rejected at construction

- **WHEN** a `Movement` is constructed with a null `accountId`, `amount`, or `type`
- **THEN** construction throws `NullPointerException` and no `Movement` instance exists

#### Scenario: No mutation API on persisted movements

- **WHEN** the production sources under `com.bank.core.infrastructure.persistence.ledger` are inspected
- **THEN** `LedgerMovementEntity` exposes no `public` setter, no `@Modifying` query targets columns other than `journal_entry.verification_status`, and `Movement` (the domain record) has no setter by language definition

### Requirement: Journal entries are append-only

Once a `JournalEntry` is persisted, its movements, description, timestamp, identifier, and movements' account references SHALL NOT be mutable. The verification status is the only mutable column on the `journal_entry` row, and is governed by the transition rules above.

#### Scenario: No mutation API on persisted journals

- **WHEN** the production sources are inspected
- **THEN** the only mutable column on `journal_entry` is `verification_status` (set via dirty-checking on the loaded entity inside `JournalEntriesJpaAdapter.save`); no production code path updates `description`, `entry_timestamp`, `id`, or any column on `ledger_movement`; the adapter's public methods perform `save` (insert or status-only update), `findById` (read), `findByStatus` (read), and `isBalanced` (read)

### Requirement: Monotonic movement identifiers

Each `ledger_movement` row SHALL have a `BIGINT` primary key assigned by the database as an `IDENTITY` column. Identifiers SHALL be assigned in strictly increasing order across the whole system, regardless of which journal the movement belongs to, so audit features (see [[balance-drift-detection]]) can use the id as a cursor.

#### Scenario: Movement ids are globally monotonic across journals

- **WHEN** two journal entries J1 and J2 are persisted in sequence (J1 first, J2 second), each with at least two movements
- **THEN** every movement in J2 has an id strictly greater than every movement in J1

#### Scenario: Movement id is assigned by the database, not by the domain

- **WHEN** a `Movement` value is constructed by application code
- **THEN** the `Movement` record does not carry an id field; the id only materialises on the `LedgerMovementEntity` after `save(...)` returns

### Requirement: Database-side balance check

The `JournalEntries.isBalanced(JournalEntryId)` adapter SHALL determine whether a persisted journal balances by issuing a single aggregate query against the `ledger_movement` table for that journal id. The adapter SHALL NOT load movements into memory and sum them in Java.

#### Scenario: Balanced check runs as one aggregate query

- **WHEN** `JournalEntries.isBalanced(id)` is called for a persisted journal
- **THEN** the adapter issues exactly one `SELECT COALESCE(SUM(CASE WHEN movement_type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_movement WHERE journal_entry_id = ?` query, compares the result to `BigDecimal.ZERO` with `signum()`, and returns the boolean without invoking `findAll`/streaming over movements

#### Scenario: Balanced journal returns true; unbalanced returns false

- **WHEN** a balanced journal (sum of credits = sum of debits) is persisted, then `isBalanced(id)` is called
- **THEN** the call returns `true`; for an unbalanced journal forced into the database via a direct `JdbcTemplate` insert (bypassing the domain), `isBalanced(id)` returns `false`

#### Scenario: Non-existent journal returns false

- **WHEN** `isBalanced(id)` is called with an id that does not exist
- **THEN** the call returns `false` (the adapter checks `existsById` first to avoid the SUM-over-zero-rows trap)

### Requirement: Journal entries queryable by status with paging

The `JournalEntries.findByStatus(VerificationStatus, int limit)` adapter SHALL return at most `limit` journal entries whose `verification_status` equals the argument, ordered deterministically (by `entry_timestamp ASC, id ASC`) so background sweepers (see [[journal-verification]]) can process journals in bounded ticks with stable cross-call ordering.

#### Scenario: Pending journals listed in pages

- **WHEN** a sweeper calls `findByStatus(PENDING, 50)`
- **THEN** the result contains at most 50 journals, all with status `PENDING`, ordered by `entry_timestamp` ascending then by `id` ascending; the same call issued repeatedly with new data returns rows in the same relative order

#### Scenario: Empty result for status with no matches

- **WHEN** `findByStatus(VERIFIED, 50)` is called and no verified journals exist
- **THEN** the call returns an empty `List<JournalEntry>` (not null) without error

### Requirement: Application port stays Spring-free

The `JournalEntries` interface in `com.bank.core.application.ledger` SHALL be a plain Java interface with no Spring/JPA annotations and no imports from `org.springframework.*`, `jakarta.persistence.*`, or `org.openapitools.*`. Its method signatures SHALL use only domain types (`JournalEntry`, `JournalEntryId`, `VerificationStatus`) and JDK types (`Optional`, `List`, `boolean`, `int`).

#### Scenario: Port is plain Java

- **WHEN** the production sources of `com.bank.core.application.ledger.JournalEntries` are inspected
- **THEN** the interface declares no annotation other than JDK-standard ones (`@FunctionalInterface` not required and not present), imports nothing from `org.springframework.*` or `jakarta.persistence.*`, and the existing F00 ArchUnit `applicationHasNoFrameworkDependencies` rule continues to pass
