## Why

A continuous background audit is required to compare each account's cached balance against the sum of its ledger movements and immediately suspend any account where they disagree. This capability ensures that any database corruption, out-of-band data modification, or race condition resulting in a balance drift is caught and isolated immediately before any financial discrepancies propagate.

## What Changes

- **NEW** Flyway migration `V4__audit_checkpoint.sql` creating the `audit_checkpoint` table to track persistent audit sequence progress.
- **NEW** `AuditCheckpoints` port interface in `:application` for managing named checkpoint offsets.
- **NEW** `AuditCheckpointsJpaAdapter` and corresponding entity in `:infrastructure` to back checkpoints in the database.
- **NEW** `DetectBalanceDrift` plain-Java use case in `:application` that identifies candidates using a persistent audit checkpoint window, performs DB-side signed movement sum calculations, compares them to cached balances, and suspends drifting active accounts (with a carve-out for the clearing account).
- **NEW** `BalanceDriftAudit` Spring-managed transaction facade in `:infrastructure` exposing a transactional `@Transactional` gateway for the use case.
- **NEW** `BalanceDriftScheduler` Spring component in `:bootstrap` to periodically trigger the audit with configurable delay properties `bank.balance-drift.fixed-delay-ms` and `initial-delay-ms`.
- **NEW** Comprehensive reflection-based properties validation and integration testing for scheduling and drift suspension.

## Capabilities

### New Capabilities
- `balance-drift-detection`: Continuous checkpoint-based audit of account balances against the ledger, suspending active drifted accounts, running concurrently safe with live transfers.

### Modified Capabilities
<!-- None -->

## Impact

- **Database**: Adds `audit_checkpoint` table and index on `ledger_movement(account_id)`.
- **Application**: Integrates balance checking and checkpoint tracking domain structures.
- **Bootstrap**: Adds scheduling runner and configurable delay profiles.
