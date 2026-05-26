## ADDED Requirements

### Requirement: Idempotency-Key header on POST /api/v1/transfers

The `POST /api/v1/transfers` operation SHALL accept an optional `Idempotency-Key` request header carrying a client-chosen unique string of 1..200 ASCII characters. The header is OPTIONAL; requests without it SHALL behave exactly as without this capability (no replay protection). The header value SHALL NOT be returned in any response header.

#### Scenario: Header absent — no protection

- **WHEN** a client issues `POST /api/v1/transfers` with a valid body and no `Idempotency-Key` header
- **THEN** the service runs the transfer pipeline and returns its response
- **AND** no row is written to `idempotency_key`
- **AND** an immediate retry (also without the header) runs the pipeline again, producing a second journal entry

#### Scenario: Header present with malformed value — 400

- **WHEN** a client issues the request with `Idempotency-Key:` (empty), or with a value longer than 200 characters, or containing a non-ASCII byte
- **THEN** the response is HTTP 400 with `code = BAD_REQUEST_PAYLOAD` and a `message` naming the `Idempotency-Key` header
- **AND** no row is written to `idempotency_key`
- **AND** the transfer pipeline does not run

### Requirement: First request runs the pipeline and persists the response

When `POST /api/v1/transfers` is received with a valid `Idempotency-Key` that does not yet have a committed `idempotency_key` row, the service SHALL run the transfer pipeline exactly once, then persist a single row recording the key, the request body fingerprint, the response status, and the response body verbatim. The full sequence (claim, run, persist) SHALL occur inside the controller's existing `@Transactional` boundary so a pipeline-level rollback also rolls back the key row.

#### Scenario: First successful request persists the 204 response

- **WHEN** a client issues a valid transfer with `Idempotency-Key: <fresh-uuid>`
- **THEN** the response is HTTP 204
- **AND** the `idempotency_key` table contains exactly one row with `key=<fresh-uuid>`, `response_status=204`, `response_body=""`, a non-empty `request_fingerprint`, and `created_at` set
- **AND** the journal_entry / ledger_movement effects of the transfer are present

#### Scenario: First classified-rejection request persists the 400 envelope

- **WHEN** a client issues a transfer that would fail with insufficient funds, with `Idempotency-Key: <fresh-uuid>`
- **THEN** the response is HTTP 400 with `code = INSUFFICIENT_FUNDS`
- **AND** the `idempotency_key` table contains exactly one row with `key=<fresh-uuid>`, `response_status=400`, and `response_body` equal to the JSON envelope returned to the client (same `code`, same `message`, same `timestamp` field)
- **AND** the transfer's ledger effects are NOT present (the use case threw)

#### Scenario: Pipeline rollback also rolls back the key row

- **WHEN** the transfer pipeline throws an unclassified `RuntimeException` (simulated infrastructure failure) on a first request with a fresh key
- **THEN** the surrounding transaction rolls back
- **AND** no `idempotency_key` row is committed
- **AND** a subsequent retry with the same key is treated as a first request

### Requirement: Replay returns the stored response

When `POST /api/v1/transfers` is received with an `Idempotency-Key` whose `idempotency_key` row already exists with `status=COMPLETED` and a matching `request_fingerprint`, the service SHALL return the stored response without re-running the transfer pipeline. The returned HTTP status SHALL equal the stored `http_status`. For 4xx replays, the response body SHALL be an `ErrorEnvelope` whose `code`, `message`, and `timestamp` field values equal the stored `envelope_code`, `envelope_message`, and `envelope_timestamp` (i.e. the original first-response values, not "now"). For 204 replays, the response body SHALL be empty.

#### Scenario: Successful replay returns 204 with no body

- **WHEN** the original request returned 204 and a client retries with the same `Idempotency-Key` and the same body
- **THEN** the replay response is HTTP 204 with no body
- **AND** no additional `journal_entry` row is created
- **AND** `bank_transfer_executed_total{outcome="success"}` does NOT increase

#### Scenario: Error replay returns the original 400 envelope

- **WHEN** the original request returned 400 INSUFFICIENT_FUNDS and a client retries with the same `Idempotency-Key` and the same body
- **THEN** the replay response is HTTP 400 with `Content-Type: application/json`
- **AND** the response body is an `ErrorEnvelope` with the same `code`, `message`, and `timestamp` field values as the original first response (NOT a fresh timestamp at replay time)
- **AND** `bank_transfer_executed_total{outcome="insufficient_funds"}` does NOT increase

### Requirement: Same key with different body is rejected

If a request arrives with an `Idempotency-Key` whose row exists and whose stored `request_fingerprint` does NOT match the incoming request's fingerprint, the service SHALL reject the request with HTTP 422 and `code = IDEMPOTENCY_KEY_REUSED`. No transfer pipeline runs and no row is mutated. The fingerprint SHALL be computed as the hex SHA-256 of the canonical JSON encoding (lexicographically sorted keys, no whitespace) of the request body.

#### Scenario: Reuse with changed amount

- **WHEN** a client issues a transfer of `10.00` with `Idempotency-Key: K`, then retries with the same key but amount `20.00`
- **THEN** the second response is HTTP 422 with `code = IDEMPOTENCY_KEY_REUSED`
- **AND** the second `message` references the key and indicates a body mismatch
- **AND** no second journal_entry is created

#### Scenario: Whitespace-only difference does NOT count as a different body

- **WHEN** the original body is `{"sourceAccountNumber":"A","destinationAccountNumber":"B","amount":1.00}` and the retry body is the same content with extra whitespace and reordered keys
- **THEN** the response is the normal replay (200/204/4xx as stored), NOT 422
- **AND** the fingerprint computation matches because the canonical JSON encoding does

### Requirement: Concurrent in-flight request returns 409

If a second request arrives with an `Idempotency-Key` whose row exists with `status=PENDING` (a first request is mid-flight), the service SHALL respond with HTTP 409 and `code = CONCURRENT_IDEMPOTENT_REQUEST`. The second request SHALL NOT run the transfer pipeline. Clients SHOULD retry after a brief delay.

#### Scenario: Two simultaneous requests with the same key

- **WHEN** two threads issue `POST /api/v1/transfers` with the same `Idempotency-Key` and the same body at the same time
- **THEN** one request runs the pipeline and returns its outcome (204 or 4xx)
- **AND** the other request returns HTTP 409 with `code = CONCURRENT_IDEMPOTENT_REQUEST`
- **AND** at most one `journal_entry` row results from the pair

### Requirement: 24-hour retention; expired keys treated as fresh

The service SHALL retain `idempotency_key` rows for at least 24 hours after `created_at`. Replays older than the retention window MAY (but need not) be treated as fresh requests. A scheduled retention sweeper is OUT OF SCOPE for this capability; rows accumulate harmlessly within demo-scale lifetimes.

#### Scenario: Replay within retention returns stored response

- **WHEN** a client replays an `Idempotency-Key` whose `created_at` is less than 24 hours ago
- **THEN** the response is the stored response (per the byte-for-byte requirement above)

#### Scenario: Retention TTL is configurable but not actively enforced

- **WHEN** the service starts with default configuration
- **THEN** no `@Scheduled` job runs to delete expired `idempotency_key` rows
- **AND** the table grows monotonically with usage (documented TODO for a future change)

### Requirement: Persistence shape

The Flyway migration `V5__idempotency_key.sql` SHALL create a single table `idempotency_key` with exactly the following columns:

| Column                 | Type                       | Constraint                              |
|------------------------|----------------------------|-----------------------------------------|
| `key_value`            | VARCHAR(200)               | PRIMARY KEY (named `key_value` because `key` is a reserved word in H2 PostgreSQL mode) |
| `request_fingerprint`  | VARCHAR(64)                | NOT NULL                                |
| `status`               | VARCHAR(16)                | NOT NULL, CHECK in {PENDING, COMPLETED} |
| `http_status`          | SMALLINT                   | NOT NULL                                |
| `envelope_code`        | VARCHAR(64)                | NULL                                    |
| `envelope_message`     | VARCHAR(2000)              | NULL                                    |
| `envelope_timestamp`   | TIMESTAMP WITH TIME ZONE   | NULL                                    |
| `created_at`           | TIMESTAMP WITH TIME ZONE   | NOT NULL                                |

For success responses (`http_status = 204`), all three envelope columns SHALL be NULL. For classified-rejection responses (`http_status` in `4xx`), all three envelope columns SHALL be populated and together SHALL be sufficient to reconstruct the original `ErrorEnvelope` DTO. The migration SHALL NOT alter any existing table.

#### Scenario: Schema matches the spec exactly

- **WHEN** the application boots and Flyway runs
- **THEN** the `idempotency_key` table exists with exactly the eight columns listed above
- **AND** no existing table has been modified
- **AND** `flyway_schema_history` records `V5__idempotency_key.sql` as applied

#### Scenario: Success-response storage has NULL envelope columns

- **WHEN** a first request succeeds and writes its `idempotency_key` row
- **THEN** that row has `http_status = 204` and `envelope_code`, `envelope_message`, `envelope_timestamp` all NULL

#### Scenario: Rejection-response storage has populated envelope columns

- **WHEN** a first request fails with `INSUFFICIENT_FUNDS` and writes its `idempotency_key` row
- **THEN** that row has `http_status = 400`, `envelope_code = "INSUFFICIENT_FUNDS"`, a non-NULL `envelope_message`, and a non-NULL `envelope_timestamp` matching the timestamp returned to the client

### Requirement: Replays bypass transfer instrumentation

`bank_transfer_executed_total`, `bank_transfer_duration_seconds`, `bank_lock_acquisition_seconds`, and the journal-entry write SHALL fire on FIRST execution only. A replay returning the stored response SHALL NOT increment any of these meters and SHALL NOT produce a new journal_entry, ledger_movement, or lock acquisition.

#### Scenario: Replay does not tick transfer counters

- **WHEN** the original request was a successful transfer that incremented `bank_transfer_executed_total{outcome="success"}` by 1
- **AND** a client issues two replays with the same `Idempotency-Key` and body
- **THEN** the success counter is still equal to its post-original value (not +2 or +3)

#### Scenario: Replay does not acquire locks

- **WHEN** a client replays a successful transfer 10 times in quick succession
- **THEN** `bank_lock_acquisition_seconds_count{strategy="jvm"}` (or `db`) increases by exactly 1 across all 11 requests (one acquisition on the original; zero on each replay)
