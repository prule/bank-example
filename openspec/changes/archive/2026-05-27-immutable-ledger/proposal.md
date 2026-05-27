## Why

Currently, there is no double-entry ledger or immutable audit trail tracking the history of account balance modifications in the system. Introducing an append-only transaction ledger ensures that every movement of money is permanently documented in a balanced journal entry, establishing the absolute source of truth for financial auditing, preventing illegal state tampering, and supporting continuous background drift reconcilers.

## What Changes

- **Immutable Domain Ledger Models**: Implement pure Java domain models: `JournalEntry` (UUID-based identity, description, timestamp, status), `Movement` (record carrying Account ID, Money amount, and type), `MovementType` (DEBIT/CREDIT), and `VerificationStatus` (PENDING/VERIFIED/FAILED).
- **Custom Ledger Exceptions**: Define structured exceptions: `UnbalancedJournalException` and `IllegalJournalStatusTransitionException` extending `DomainException`.
- **Decoupled Application Port**: Introduce the Spring-free `JournalEntries` interface in the `application` layer (`com.bank.core.application.ledger`) defining all lookup, status listing, and balanced checks.
- **Ledger Schema Migration**: Create a Flyway migration script mapping `journal_entry` and `ledger_movement` tables. The movement primary key must be a globally monotonic `BIGINT IDENTITY`.
- **JPA Persistence Adapter**: Implement `JournalEntriesJpaAdapter` inside the `infrastructure` layer (`com.bank.core.infrastructure.persistence.ledger`) mapping domain entities to JPA entities with strictly package-private modifiers, supporting paginated status retrievals, and performing database-side SUM aggregate checks for balanced journals.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `immutable-ledger`: Implement balanced journal entries, monotonic ledger movements, append-only persistence rules, and clean domain interfaces.

## Impact

- **Database Table Setup**: Two new tables will be provisioned in the H2 memory database via Flyway migrations on startup.
- **Transfers Integration**: All future REST transfers and background reconcilers will rely directly on this ledger to persist movements, check status, and perform sweeps.
