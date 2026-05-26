## Why

`POST /api/v1/transfers` is the only write endpoint and it currently has no replay protection. A client that issues a transfer, suffers a network blip, then retries on its own initiative may produce two debits when only one was intended — exactly the class of bug a ledger system most needs to prevent. The fix is the industry-standard pattern (Stripe, AWS, GitHub, etc.): an optional `Idempotency-Key` request header, a small server-side store mapping key → original response, and replay-on-collision instead of re-execute.

This is the most "wait, banks really need this" moment in the demo today, and it fits naturally with the existing transactional and locking machinery — the same `@Transactional` boundary on `TransferController.createTransfer` is exactly the right place to claim the key, run the transfer, and persist the response together.

## What Changes

- **OpenAPI contract**: declare `Idempotency-Key` as an optional `header` parameter on `POST /api/v1/transfers`. Document the new `409 Conflict` response (concurrent request already in flight for the same key) and `422 Unprocessable Entity` response (`Idempotency-Key` reused with a different request body).
- **Persistence**: new Flyway migration `V5__idempotency_key.sql` creating a single table `idempotency_key` with columns `key VARCHAR(200) PK`, `request_fingerprint VARCHAR(64) NOT NULL`, `status VARCHAR(16) NOT NULL CHECK in {PENDING, COMPLETED}`, `http_status SMALLINT NOT NULL`, `envelope_code VARCHAR(64) NULL`, `envelope_message TEXT NULL`, `envelope_timestamp TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ NOT NULL`. For `204` responses, all envelope columns are NULL; for `4xx` they reconstruct an `ErrorEnvelope` byte-stably (the generated DTO pins field order). `request_fingerprint` is the hex SHA-256 of the canonicalised request body (sorted keys, no whitespace) — enough to detect "same key, different transfer".
- **Domain → application port**: new `IdempotencyStore` port in `application/` with a single method:
  - `executeIdempotent(IdempotencyKey, RequestFingerprint, Supplier<ResponseRecord> work) → ResponseRecord` — atomically claim the key, run the supplier on first occurrence, return the stored response on replay, throw `IdempotencyConflictException` on concurrent in-flight, throw `IdempotencyKeyReuseException` on same-key-different-body.
- **Controller**: read the `Idempotency-Key` header (optional). When present, route through `IdempotencyStore`; when absent, behave as today (no protection). Either way the use case still runs through `TransferMetrics` so the existing observability instrumentation continues to fire (success / classified rejection counters tick on the original request, NOT on replays — replays don't run the use case at all).
- **Error mapping**: extract the exception-to-envelope conversion currently inside `GlobalExceptionHandler` into a small reusable `ErrorEnvelopeMapper` component, so the idempotency wrapper can serialise the SAME envelope the global handler would return and store it for later replay without duplicating the mapping table.
- **Behaviour rules**:
  - `Idempotency-Key` header is optional. Absent → today's behaviour (no replay protection).
  - Key format: 1..200 ASCII characters; rejected with `400 BAD_REQUEST_PAYLOAD` if violated.
  - Key TTL: keys are retained for 24 hours after `created_at`. Replays after the TTL are treated as new requests. A retention sweeper is **out of scope for this change** (documented TODO).
  - Replay of a stored result returns the original response **byte-for-byte** — same status, same envelope, same `code` enum, same `timestamp`. This means a 400 INSUFFICIENT_FUNDS replay returns the original 400 INSUFFICIENT_FUNDS (industry standard; the alternative — retrying the use case — defeats the point).
  - The original request's effects (`Transfer*` metric tick, journal_entry row, lock acquisition) happen on the first request only. Replays touch neither the use case nor the locker.
  - Concurrent in-flight: two requests arrive with the same key while the first is mid-flight. The second SHALL receive `409 CONCURRENT_IDEMPOTENT_REQUEST`. Clients retry after a small delay (or on the next 200/4xx the server hands them).
  - Same key, different body: rejected with `422 IDEMPOTENCY_KEY_REUSED`. The fingerprint comparison is the gate; document that key reuse with a deliberately different body indicates a client bug.

Out of scope:
- Retention sweeper (a `@Scheduled` job deleting `idempotency_key` rows older than 24h). Documented as a TODO in design.md.
- Cross-instance coordination beyond what Postgres's row-level `INSERT ... ON CONFLICT` already provides. The H2 production database is single-instance; multi-instance deployments inherit Postgres's atomicity.
- Idempotency for any other endpoint (e.g. `POST /api/v1/accounts` if/when it lands). One endpoint, one table.
- Idempotency-Key on `GET` endpoints (HTTP semantics already guarantee idempotency).

## Capabilities

### New Capabilities
- `transfer-idempotency`: contract for the `Idempotency-Key` header on `POST /api/v1/transfers` — header semantics, replay rules, conflict semantics, retention policy, error codes, and the `idempotency_key` storage shape.

### Modified Capabilities
- `fund-transfer`: the transfer endpoint now ALSO accepts the optional `Idempotency-Key` header. The existing transfer-behaviour requirements (debit, credit, journal entry, locking) stay exactly as today; the delta is purely additive — they now hold only on the first occurrence of a given key.
- `api-error-contract`: the canonical error envelope's `code` enum gains two new values: `CONCURRENT_IDEMPOTENT_REQUEST` (HTTP 409) and `IDEMPOTENCY_KEY_REUSED` (HTTP 422). All other codes unchanged.

## Impact

- **Code**:
  - New: `IdempotencyStore` port in `application/`; `IdempotencyJpaAdapter` + `IdempotencyKeyEntity` + `IdempotencyKeyRepository` in `infrastructure/`; `IdempotencyKey` and `RequestFingerprint` value objects in `domain/`; `IdempotencyConflictException` and `IdempotencyKeyReuseException` in `domain/`; `ErrorEnvelopeMapper` extracted from `GlobalExceptionHandler`.
  - Edited: `TransferController` reads the header and branches; `GlobalExceptionHandler` adds two new `@ExceptionHandler` methods and delegates envelope construction to `ErrorEnvelopeMapper`; OpenAPI yaml (path + schemas).
- **Schema**: one new Flyway migration `V5__idempotency_key.sql`. No edits to existing migrations or tables.
- **OpenAPI**: `Idempotency-Key` header parameter added to one operation; two new error responses on the same operation; two new enum values in the `ErrorEnvelope.code` schema.
- **Tests**: new unit tests for `IdempotencyJpaAdapter` (key-claim race, replay, fingerprint mismatch); new `@SpringBootTest` covering the four end-to-end scenarios (no key → today's behaviour; first request → 204; replay → same 204 byte-for-byte; same key different body → 422; concurrent in-flight → 409). `OpenApiContractTest` automatically picks up the contract change.
- **Metrics**: no new Micrometer instrumentation. Replays are short-circuit returns that bypass `TransferMetrics`, so `bank_transfer_executed_total` continues to count *actual* attempts, not "HTTP requests landing at the endpoint" — which is the intended semantics. We might want a separate `bank.idempotency.replay_total` counter in a future change but it's not in this one.
- **Wire compatibility**: clients that don't send the header see byte-identical behaviour to today. The header is purely opt-in.
- **No impact on**: account lookup, HATEOAS shape, scheduler cadence, balance drift detection, journal verification, observability stack, dashboard, load generator.
