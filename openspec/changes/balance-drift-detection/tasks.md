## 1. Schema Migration and Persistence Adapter

- [ ] 1.1 Create the Flyway database migration script `V4__audit_checkpoint.sql` to provision the `audit_checkpoint` table.
- [ ] 1.2 Implement the `AuditCheckpoints` application port interface in `:application` package `com.bank.core.application.ledger`.
- [ ] 1.3 Create the `AuditCheckpointEntity` JPA class and repository in `:infrastructure` under persistence packages.
- [ ] 1.4 Implement the `AuditCheckpointsJpaAdapter` in `:infrastructure` implementing the `AuditCheckpoints` port.
- [ ] 1.5 Expand `JournalEntries` port and JPA implementation adapter to support `currentCeiling()`, `distinctAccountIdsInWindow()`, and `sumSignedAmountForAccount()` aggregate queries.

## 2. Core Domain and Use Case Implementation

- [ ] 2.1 Implement the `DriftReport` Java record in the `:application` module under package `com.bank.core.application.account`.
- [ ] 2.2 Implement the plain-Java use case `DetectBalanceDrift` under package `com.bank.core.application.account` executing audit sequence logic.
- [ ] 2.3 Create the `BalanceDriftAudit` Spring-managed transaction facade in the `:infrastructure` module.

## 3. Infrastructure Scheduling and Verification

- [ ] 3.1 Implement the `BalanceDriftScheduler` Spring scheduled component in the `:bootstrap` module.
- [ ] 3.2 Wire the new beans in the `BankCoreApplication` bootstrap class.
- [ ] 3.3 Write comprehensive unit tests for `DetectBalanceDrift` use case logic covering normal matching, active drift, closed/suspended exclusion, clearing carve-outs, and error recovery using lightweight Mockito-free test fakes.
- [ ] 3.4 Write end-to-end integration tests in the `:bootstrap` module verifying scheduling execution, live H2 audit progress, drift suspensions, and logger output.
