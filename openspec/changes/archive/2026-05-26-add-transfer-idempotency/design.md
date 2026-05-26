## Context

The single write endpoint, `POST /api/v1/transfers`, is `@Transactional` and currently runs the full pipeline on every request: lock acquisition, debit/credit, journal entry, ledger movements. A retry after a transient network failure (response lost before reaching the client; client retries; server runs the pipeline twice) produces two real debits. The industry-standard mitigation is an `Idempotency-Key` header carrying a client-chosen unique token, plus a server-side store that maps key → first response and replays it for subsequent requests bearing the same key.

The existing infrastructure shape lines up unusually well:
- The `@Transactional` boundary is already on the controller method, so wrapping "claim key + run + persist response" in a single transaction is one annotation change away.
- `GlobalExceptionHandler` already centralises exception → envelope mapping; pulling its mapping logic into a reusable `ErrorEnvelopeMapper` is a refactor we want regardless of idempotency, and it unblocks the wrapper from having to duplicate that table.
- The `TransferMetrics` wrapper is the right level for "first execution only" — replays bypass it and the underlying use case entirely.
- The contract-first capability gives us a clean place to add the header parameter (`bootstrap/src/main/resources/openapi/paths/transfers.yaml`) without inventing a parallel doc.

Stakeholders: the study reader walking through the v2 branch (this is the most "wait, banks need this" upgrade); the operator running `generate-load.sh` (no change to behaviour without the header); the eventual `v3-clean` rebuild (idempotency lands as a spec, not a folk convention).

## Goals / Non-Goals

**Goals:**
- Optional `Idempotency-Key` header. Absent → today's behaviour, byte-identical.
- Present + first request → run the pipeline, persist the response, return it.
- Present + replay → return the persisted response byte-for-byte (status, body, code, timestamp) without re-running the pipeline.
- Present + same key + different body → `422 IDEMPOTENCY_KEY_REUSED`; refuse to silently overwrite.
- Present + concurrent in-flight → `409 CONCURRENT_IDEMPOTENT_REQUEST`; refuse to silently merge.
- Storage atomicity guaranteed by the database (unique-index INSERT on PK), not by application-level locks.
- The transfer's metric counters (`bank_transfer_executed_total`, `bank_transfer_duration_seconds`) tick on the *first* execution only — replays are not executions.

**Non-Goals:**
- Cross-instance distributed coordination beyond what the database row-level constraint already provides.
- Idempotency for `GET` (HTTP-level idempotent by definition) or for any other write endpoint (none exist yet).
- A scheduled retention sweeper for the `idempotency_key` table. Documented TODO; rows accumulate harmlessly within demo-scale lifetimes.
- A new Micrometer counter for replays. Future work if needed.
- Cryptographic strength on the fingerprint. SHA-256 of canonical JSON is far more than enough to detect accidental body changes; an attacker can already mount worse attacks given there's no auth.

## Decisions

### Decision 1: Optional header, not required

Making the header mandatory would be a breaking contract change for any existing client and a UX speed-bump for `curl`/Swagger users exploring the API. The header is opt-in: clients that care about retry-safety provide it; clients that don't get today's behaviour. This is also Stripe's posture.

**Alternative considered**: require the header for all POSTs. Rejected — too coarse for a study app; the contract change is invasive without a real consumer to demand it.

### Decision 2: Structured response storage, not literal JSON bytes

The replay needs to return an envelope whose `code`, `message`, and `timestamp` field values are identical to the first response. Two storage shapes can deliver that:

- **Literal-JSON column** (`response_body TEXT`): store the exact bytes Jackson produced. Replay returns those bytes verbatim. Requires bypassing Spring's content negotiation (write directly to `HttpServletResponse`) because the generated `TransfersApi.createTransfer(...)` signature is `ResponseEntity<Void>` — there's no clean way to return a `ResponseEntity<String>` body from a `Void`-typed method without falling back to servlet-level writes.
- **Structured columns** (`http_status`, `envelope_code`, `envelope_message`, `envelope_timestamp`, all nullable except status): reconstruct an `ErrorEnvelope` DTO on replay, return `ResponseEntity<ErrorEnvelope>(envelope, status)`, let Spring's Jackson serialise. The envelope's `@JsonProperty` annotations pin field order, so the bytes are stable across replays. The captured timestamp is the original timestamp (preserved by the column), so replays carry that, not "now".

Choosing **structured**. The code reads as plain JPA, replays use Spring's standard response path (no servlet-level hacks), and the byte stability we need actually holds because the generated DTO has a fixed field order. The literal-bytes approach is more "Stripe says do this", but they have different framework constraints (and there are no contract-first generated DTOs).

For `204 No Content`, all envelope columns are NULL; `http_status` alone drives the response. For 4xx, all three envelope columns are populated.

**Alternative considered**: literal-JSON column. Rejected on the response-shaping cost: the `HttpServletResponse` write path is gross and undermines the clean-controller story this codebase has elsewhere.

### Decision 3: Atomicity via a single transaction + unique-index INSERT race

The controller is already `@Transactional`. Inside that transaction:

1. INSERT a row `(key, fingerprint, status=PENDING, response='', created_at=now())`. If the unique constraint fires, GOTO step 4. Else we own this key.
2. Run the transfer (existing pipeline: lock-acquire, debit, credit, journal). Catch all *classified* exceptions; serialise them via `ErrorEnvelopeMapper` into an envelope JSON string and a status code. Uncaught exceptions propagate (no row update; the transaction rolls back, the next request will see a clean slate).
3. UPDATE the row to `(status=COMPLETED, response=<json>, response_status=<int>)`. Commit. Return the response.
4. (Conflict path) SELECT the existing row. If `status=COMPLETED` and the stored `fingerprint` matches our request, return the stored response. If `status=COMPLETED` and the fingerprint mismatches, throw `IdempotencyKeyReuseException` (→ 422). If `status=PENDING`, throw `IdempotencyConflictException` (→ 409).

This is **one transaction per request**, holds locks only on its own `idempotency_key` row, and uses the database's atomicity directly. The conflict path's `SELECT` may see an uncommitted PENDING row from a concurrent first-request transaction (depending on isolation level); on H2 and Postgres default isolation (READ_COMMITTED), it won't — the SELECT will block until the first transaction commits, then read the committed result. Either way, the conflict path returns the correct answer.

There's a subtle wrinkle: if the original transaction rolls back (the pipeline threw an unclassified `RuntimeException`), the PENDING row vanishes with the rollback. A retrying client then re-attempts as if the key were never used. This is correct: we never PERSISTED a result for a transaction-rolled-back attempt, so there's nothing to replay.

**Alternative considered**: optimistic two-transaction pattern (claim in TX-A, run in TX-B). Rejected — the locking contract on the existing pipeline assumes one transaction wrapping the whole thing. Splitting would require reasoning about partial states between TX-A and TX-B that aren't worth the complexity.

### Decision 4: Fingerprint = SHA-256 of canonical JSON, not raw body

The raw `application/json` body can vary in whitespace and key order between Jackson serialisations of the same logical content. Two requests that mean the same thing but happen to differ in formatting would falsely flag as IDEMPOTENCY_KEY_REUSED. Canonicalisation:

- Deserialise to `Map<String, Object>`.
- Sort keys lexicographically.
- Re-serialise with no whitespace.
- SHA-256 hex.

The `TransferRequest` schema is small (three fields) so the canonicalisation cost is trivial. The fingerprint is a 64-char ASCII hex string, well-bounded for the column type.

**Alternative considered**: hash the raw bytes. Rejected — fails on whitespace variance, which is a real client concern.

### Decision 5: New `ErrorEnvelopeMapper` extracted from `GlobalExceptionHandler`

`GlobalExceptionHandler` today has the mapping table inline. The idempotency wrapper needs to produce the same envelope for the same exception so the stored response matches what the global handler would have returned. Rather than duplicate the table, extract:

```java
@Component
public class ErrorEnvelopeMapper {
    public ResponseRecord toResponse(Throwable ex) {
        return switch (ex) {
            case InsufficientFundsException ifx -> ...
            ...
        };
    }
}
```

Both `GlobalExceptionHandler` and the idempotency wrapper depend on this mapper. The handler still does request-context-aware logging (HTTP method, URI); the mapper is purely exception → (status, envelope) — DTO logic, no IO.

This extraction has standalone value (DRY, testable in isolation) and is the cleanest way to keep idempotency replay semantically identical to a fresh request's error response.

### Decision 6: `IdempotencyStore` port + `executeIdempotent(...)` shape

The application module gets a port:

```java
public interface IdempotencyStore {
    ResponseRecord executeIdempotent(IdempotencyKey key,
                                     RequestFingerprint fingerprint,
                                     Supplier<ResponseRecord> work);
}
```

`ResponseRecord` is a small immutable record `(int status, String body)`. The port hides the JPA adapter behind a plain-Java method so the controller stays framework-light.

The implementation lives in `infrastructure/` as a Spring `@Component @Transactional` whose `executeIdempotent` performs the INSERT/UPDATE dance described in Decision 3. Because both the existing transfer pipeline and the new idempotency wrapper need to be inside the same transaction, the controller's existing `@Transactional` continues to define the boundary; the `IdempotencyStore` adapter does NOT start its own.

**Alternative considered**: have the adapter own the transaction (`@Transactional(propagation = REQUIRES_NEW)`). Rejected — splits atomicity guarantees in a way that would let the response-row INSERT commit before the pipeline's debit/credit, defeating the whole point.

### Decision 7: Header parsing in the controller, not a filter

A filter or interceptor would let us treat idempotency generically. We have one endpoint that needs it. The controller is the right place — it already owns request-DTO mapping and `@Transactional`. Reading one header and branching is half a dozen lines. A filter buys nothing.

If a future change extends idempotency to a second write endpoint, revisit and consider a `@Idempotent` annotation + advice. Out of scope here.

## Risks / Trade-offs

- **Risk**: storing the literal JSON means the stored representation could drift from the contract if `TransferRequest` evolves. → Mitigation: replays don't care about future contract — they replay what the original handler returned. A v2 of the endpoint would have a separate path and would not share idempotency state.
- **Risk**: clients abuse the header (random per-request) effectively disabling protection. → Acceptable; protection is opt-in and the cost of an unused key is one row.
- **Risk**: the `idempotency_key` table grows unbounded without a sweeper. → Documented TODO; demo lifetimes don't suffer; production deployments add a `@Scheduled` cleanup as a follow-up.
- **Risk**: 24h TTL is hard-coded and not advertised to the client. → Acceptable for a study app; production would document via response header or contract.
- **Trade-off**: byte-identical replay requires storing the raw body, which means the database holds JSON. A schema-pure view would store structured fields; the trade-off is replay correctness vs schema purity. Replay correctness wins.

## Migration Plan

1. Land the Flyway migration + the JPA adapter + the port. Existing tests stay green (the table exists but nothing reads it yet).
2. Land the `ErrorEnvelopeMapper` extraction. `GlobalExceptionHandler` now delegates; existing tests stay green.
3. Land the controller branch + OpenAPI contract change. The new behaviour gates on header presence — clients sending no header see no change.
4. Add the new tests covering each of the four scenarios.
5. Update `ReadMe.md` Discovery section with a short note: "Retry-safe POSTs: send `Idempotency-Key: <uuid>`."

**Rollback**: drop the controller branch; the migration leaves the table behind but unused — harmless.

## Open Questions

- Should we add a `Idempotency-Replayed: true` response header to indicate a cached replay? Industry convention varies (Stripe uses `Idempotency-Replayed`; some don't). Current plan: no — keeps the response byte-identical. Revisit if a real client asks.
- Should the fingerprint canonicalisation use a JCS (RFC 8785) library or the inline three-step approach above? Inline is fine for `TransferRequest`'s flat shape; JCS becomes interesting if requests grow nested objects. Pick JCS later if shape changes.
