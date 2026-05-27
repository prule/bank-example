## Context

The application maintains cached account balances in the `account` table (per `:domain` `Account.balance`). For consistency and auditability, these cached values must always equal the signed sum of all ledger movements recorded for that account in the `ledger_movement` table. However, out-of-band updates, concurrent race conditions, or database corruptions could cause these values to drift. This design introduces a background balance drift detection engine that periodically audits the ledger, detects discrepancies, and immediately suspends drifted active accounts.

## Goals / Non-Goals

**Goals:**
- Continuously and asynchronously audit account balances against their ledger movement histories.
- Use a persistent, monotonic checkpoint structure to guarantee that every movement is audited exactly once and no movements are missed, even across restarts.
- Isolate balance drift on a per-account basis and transition drifted active accounts to `SUSPENDED` status.
- Keep the audit process concurrent-safe and non-blocking with respect to live fund transfers.
- Provide a clean heartbeat signal logging tick executions.

**Non-Goals:**
- Attempting to auto-correct or repair the drifted balance in Java or SQL (requires manual investigation).
- Auditing or auto-suspending the clearing account (it is explicitly carved out from auto-suspension).

## Decisions

### Decision 1: Persistent Checkpoint Store
We will introduce the `audit_checkpoint` table to track the audited offset.
- **Approach**: Table `audit_checkpoint(audit_name VARCHAR(64) PK, last_movement_id BIGINT NOT NULL CHECK >= 0)`.
- **Port**:
  ```java
  package com.bank.core.application.ledger;
  public interface AuditCheckpoints {
      long readOrZero(String auditName);
      void save(String auditName, long lastMovementId);
  }
  ```
- **Entity**: `AuditCheckpointEntity` in `:infrastructure` mapping to `audit_checkpoint`.
- **Adapter**: `AuditCheckpointsJpaAdapter` in `:infrastructure`.

### Decision 2: Ledger Movement Query Port
We will expand the `JournalEntries` port to provide movement-specific aggregate queries.
- **Approach**: Adding the following methods to `JournalEntries`:
  ```java
  long currentCeiling();
  List<com.bank.core.domain.AccountId> distinctAccountIdsInWindow(long floor, long ceiling);
  java.math.BigDecimal sumSignedAmountForAccount(com.bank.core.domain.AccountId id);
  ```
- **Rationale**: Reuses the ledger adapter for clean DB-side JPA/native queries:
  - `currentCeiling`: `SELECT COALESCE(MAX(m.id), 0) FROM ledger_movement m`
  - `distinctAccountIdsInWindow`: `SELECT DISTINCT a.id FROM ledger_movement m JOIN account a ON m.account_number = a.account_number WHERE m.id > :floor AND m.id <= :ceiling`
  - `sumSignedAmountForAccount`: `SELECT COALESCE(SUM(CASE WHEN m.type = 'CREDIT' THEN m.amount ELSE -m.amount END), 0) FROM ledger_movement m JOIN account a ON m.account_number = a.account_number WHERE a.id = :id`

### Decision 3: Framework-Free Core Orchestration
The use case is implemented in plain Java in `:application`.
- **Approach**: Class `DetectBalanceDrift` in `com.bank.core.application.account`.
  ```java
  public class DetectBalanceDrift {
      public static final String AUDIT_NAME = "balance_drift";
      ...
      public DriftReport audit(String clearingAccountNumber) { ... }
  }
  ```
  - Coordinates checkpoint boundaries: `floor` (from `readOrZero`) and `ceiling` (from `currentCeiling`).
  - Audits candidate account list: skips clearing account (emits INFO log), skips CLOSED/SUSPENDED accounts, recomputes balance `expected = Money.of(max(0, sumSignedAmountForAccount(id)))`, and suspends active accounts if `expected != balance`.
  - Saves the checkpoint value to `ceiling`.

### Decision 4: Spring Transactional Gateway Facade
A transactional service facade ensures checkpoint advances commit atomically with account suspensions.
- **Approach**: Introduce `BalanceDriftAudit` as a Spring `@Service` in `:infrastructure` or `:bootstrap`:
  ```java
  @Service
  public class BalanceDriftAudit {
      private final DetectBalanceDrift useCase;
      private final String clearingAccountNumber;
      
      @Transactional
      public DriftReport audit() {
          return useCase.audit(clearingAccountNumber);
      }
  }
  ```

### Decision 5: Non-overlapping Background Scheduler
`BalanceDriftScheduler` in `:bootstrap` executes the tick at configured intervals.
- **Approach**: `@Scheduled(fixedDelayString = "${bank.balance-drift.fixed-delay-ms:30000}", initialDelayString = "${bank.balance-drift.initial-delay-ms:15000}")`.
- Uses `fixedDelayString` to prevent execution overlap.

## Risks / Trade-offs

- **[Risk] High Candidates Volume** -> If a tick covers a huge number of movements, loading them all in memory could take time.
  - *Mitigation*: The candidate query uses `SELECT DISTINCT` which shrinks the list to active accounts only, and the query is indexed.
- **[Risk] Concurrent Transfers Locking** -> Account suspensions could block concurrent transfers if lock contention occurs.
  - *Mitigation*: The audit only writes `account` rows when a drift is *actually* detected (which is rare). It reads movement aggregates without row locks.
