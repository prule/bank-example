# Metrics Exposure

## Purpose

The HTTP-scrape contract for the bank service: which actuator endpoints are web-exposed, which custom `bank_*` metric names and tags the service emits, and the cardinality bounds those tags must respect. Owns the Prometheus surface so operators and dashboards depend on a stable set of metric names. The instrumentation lives in the `infrastructure` module — neither `domain` nor `application` may depend on Micrometer (ArchUnit-enforced).

## Requirements

### Requirement: Prometheus scrape endpoint

The service SHALL expose Prometheus-format metrics over HTTP at `/actuator/prometheus` on the application port.

#### Scenario: Prometheus endpoint is reachable
- **WHEN** a client issues `GET /actuator/prometheus` against a running instance
- **THEN** the response status SHALL be `200 OK`
- **AND** the response `Content-Type` SHALL begin with `text/plain` and indicate OpenMetrics or Prometheus text format
- **AND** the response body SHALL contain at least one line matching the `^# HELP ` prefix

#### Scenario: Health endpoint remains available
- **WHEN** a client issues `GET /actuator/health` against a running instance
- **THEN** the response SHALL be `200 OK` with the existing health payload (show-details=always, show-components=always)

### Requirement: Actuator exposure set

The service SHALL expose exactly the actuator endpoints `health`, `info`, `metrics`, and `prometheus` over HTTP. No other actuator endpoints SHALL be web-exposed by default.

#### Scenario: Exposed endpoints are reachable
- **WHEN** a client issues `GET /actuator/{health,info,metrics,prometheus}` in turn
- **THEN** each response status SHALL be `200 OK`

#### Scenario: Non-exposed endpoints are not reachable
- **WHEN** a client issues `GET /actuator/env`
- **THEN** the response status SHALL be `404 Not Found`

### Requirement: JVM and HTTP baseline metrics

The service SHALL emit the Spring Boot / Micrometer default JVM and HTTP server metrics in the Prometheus scrape output.

#### Scenario: JVM metrics present
- **WHEN** the Prometheus endpoint is scraped
- **THEN** the output SHALL contain at minimum `jvm_memory_used_bytes`, `jvm_threads_live_threads`, and `process_cpu_usage`

#### Scenario: HTTP server metrics present
- **WHEN** a request has been served and the Prometheus endpoint is then scraped
- **THEN** the output SHALL contain `http_server_requests_seconds_count` with at least one sample

### Requirement: Custom transfer metrics

The service SHALL emit a counter and a timer for every attempted fund transfer. The instrumentation SHALL live at the infrastructure boundary that owns the transfer's `@Transactional` (currently `TransferController.createTransfer(...)`), classifying the outcome by caught-exception type and re-throwing so the existing error-handling and rollback semantics are unchanged.

The counter SHALL be named `bank.transfer.executed` (Prometheus name `bank_transfer_executed_total`) and SHALL be tagged `outcome` with one of: `success`, `insufficient_funds`, `account_suspended`, `lock_timeout`. The timer SHALL be named `bank.transfer.duration` (Prometheus name `bank_transfer_duration_seconds`) and SHALL record the wall-clock duration of the use-case call, regardless of outcome.

#### Scenario: Successful transfer increments the success counter
- **WHEN** a call to the transfer use case returns without throwing
- **THEN** `bank_transfer_executed_total{outcome="success"}` SHALL increase by exactly 1
- **AND** `bank_transfer_duration_seconds_count` SHALL increase by exactly 1

#### Scenario: Insufficient-funds transfer increments the insufficient_funds counter
- **WHEN** the transfer use case throws `com.bank.core.domain.InsufficientFundsException`
- **THEN** `bank_transfer_executed_total{outcome="insufficient_funds"}` SHALL increase by exactly 1
- **AND** the exception SHALL be re-thrown so the surrounding transaction rolls back and the existing error envelope is produced

#### Scenario: Suspended-account transfer increments the account_suspended counter
- **WHEN** the transfer use case throws `com.bank.core.domain.AccountInactiveException`
- **THEN** `bank_transfer_executed_total{outcome="account_suspended"}` SHALL increase by exactly 1
- **AND** the exception SHALL be re-thrown unchanged

#### Scenario: Lock-timeout transfer increments the lock_timeout counter
- **WHEN** the transfer use case throws `com.bank.core.domain.LockAcquisitionTimeoutException`
- **THEN** `bank_transfer_executed_total{outcome="lock_timeout"}` SHALL increase by exactly 1
- **AND** the exception SHALL be re-thrown unchanged

#### Scenario: Other exceptions do not increment any outcome counter
- **WHEN** the transfer use case throws any exception other than the four classified above (e.g. `SameAccountTransferException`, `ResourceNotFoundException`, or an unexpected `RuntimeException`)
- **THEN** no `bank_transfer_executed_total{outcome=...}` series SHALL increase
- **AND** the exception SHALL be re-thrown unchanged
- **AND** `bank_transfer_duration_seconds_count` MAY increase by 1 (the timer records every attempt regardless of outcome)

### Requirement: Custom lock-acquisition metric

The service SHALL emit a timer named `bank.lock.acquisition` (Prometheus name `bank_lock_acquisition_seconds`) tagged `strategy` with one of `jvm` or `db`, recording the wall-clock time spent acquiring the paired account write-lock on each transfer.

#### Scenario: Successful lock acquisition is timed
- **WHEN** a transfer successfully acquires both account locks
- **THEN** `bank_lock_acquisition_seconds_count{strategy="jvm"}` (or `strategy="db"` if the DB locker is active) SHALL increase by exactly 1

#### Scenario: Timed-out lock acquisition is timed
- **WHEN** a lock acquisition times out and raises `LockAcquisitionTimeoutException`
- **THEN** `bank_lock_acquisition_seconds_count` for the active strategy SHALL increase by exactly 1

### Requirement: Custom journal-verification metrics

The service SHALL emit a counter named `bank.journal.verification` (Prometheus name `bank_journal_verification_total`) tagged `outcome` with one of `verified` or `failed`, and a gauge named `bank.journal.pending` (Prometheus name `bank_journal_pending`) reporting the current count of journal rows in `PENDING` status.

#### Scenario: A balanced journal verifies
- **WHEN** the journal verifier promotes a journal entry from `PENDING` to `VERIFIED`
- **THEN** `bank_journal_verification_total{outcome="verified"}` SHALL increase by exactly 1

#### Scenario: An unbalanced journal fails
- **WHEN** the journal verifier promotes a journal entry from `PENDING` to `FAILED`
- **THEN** `bank_journal_verification_total{outcome="failed"}` SHALL increase by exactly 1

#### Scenario: Pending gauge reflects current count
- **WHEN** the Prometheus endpoint is scraped while N journal rows have status `PENDING`
- **THEN** `bank_journal_pending` SHALL equal N

### Requirement: Custom balance-drift and suspension metrics

The service SHALL emit a counter named `bank.balance-drift.detected` (Prometheus name `bank_balance_drift_detected_total`) incremented once per account flagged by the balance drift detector, and a counter named `bank.account.suspended` (Prometheus name `bank_account_suspended_total`) tagged `cause` with one of `drift`, `journal_failure`, or `manual`.

#### Scenario: Drift detector flags an account
- **WHEN** the balance drift detector identifies an account whose cached balance differs from its ledger movement sum and suspends it
- **THEN** `bank_balance_drift_detected_total` SHALL increase by exactly 1
- **AND** `bank_account_suspended_total{cause="drift"}` SHALL increase by exactly 1

#### Scenario: Journal failure cascades to suspension
- **WHEN** a journal entry transitions to `FAILED` and the verifier suspends every account it touched
- **THEN** `bank_account_suspended_total{cause="journal_failure"}` SHALL increase by the number of accounts suspended in that cascade

### Requirement: Metric-tag cardinality discipline

Custom metric tags SHALL draw their values from a closed, compile-time-known set. No custom metric SHALL be tagged with an account number, account id, customer name, journal id, request id, or any other user-controlled or unbounded value.

#### Scenario: No unbounded tags appear in scrape output
- **WHEN** the Prometheus endpoint is scraped after 1,000 distinct transfers across 100 distinct accounts
- **THEN** the cardinality of every `bank_*` series SHALL be bounded by the product of its declared tag enums (e.g. `bank_transfer_executed_total` SHALL have at most 4 series — one per `outcome` value)

### Requirement: Domain and application modules independent of Micrometer

Neither the `domain` nor the `application` Gradle module SHALL depend on `io.micrometer` packages at compile time. All metric instrumentation SHALL live in the `infrastructure` module.

#### Scenario: ArchUnit forbids domain → Micrometer
- **WHEN** the ArchUnit boundary-discipline test suite runs
- **THEN** a rule SHALL assert that no class under `com.bank.core.domain..` imports any type from `io.micrometer..`
- **AND** that rule SHALL pass

#### Scenario: ArchUnit forbids application → Micrometer
- **WHEN** the ArchUnit boundary-discipline test suite runs
- **THEN** a rule SHALL assert that no class under `com.bank.core.application..` imports any type from `io.micrometer..`
- **AND** that rule SHALL pass
