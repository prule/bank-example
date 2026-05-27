## 1. Domain Models and Enums

- [x] 1.1 Create the JournalEntryId, MovementType, and VerificationStatus enums/types in com.bank.core.domain.
- [x] 1.2 Implement the Movement record validating strictly positive amounts and null safety at construction.
- [x] 1.3 Implement the JournalEntry rich domain model with private constructor, static factory validating balancing, and named state transition mutators.
- [x] 1.4 Implement the UnbalancedJournalException and IllegalJournalStatusTransitionException subclasses of DomainException.

## 2. Decoupled Application Port

- [x] 2.1 Define the plain Java, Spring-free JournalEntries interface in com.bank.core.application.ledger.

## 3. Database Schema Migration

- [x] 3.1 Create the Flyway database migration script in bootstrap resources defining the journal_entry and ledger_movement tables.

## 4. JPA Persistence Entities and Adapter

- [x] 4.1 Create JournalEntryEntity and LedgerMovementEntity with package-private column modifiers inside the infrastructure module.
- [x] 4.2 Implement JournalEntriesJpaAdapter in infrastructure performing inserts, paginated status queries, and a single database aggregate query for balancing.

## 5. Verification and Validation

- [x] 5.1 Write comprehensive unit and integration tests verifying double-entry domain invariants, database migration mapping, pagination, and aggregate SUM queries.
