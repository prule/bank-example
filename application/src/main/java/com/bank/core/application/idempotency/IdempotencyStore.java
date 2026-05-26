package com.bank.core.application.idempotency;

import com.bank.core.domain.IdempotencyConflictException;
import com.bank.core.domain.IdempotencyKey;
import com.bank.core.domain.IdempotencyKeyReuseException;
import com.bank.core.domain.RequestFingerprint;

import java.util.function.Supplier;

/**
 * Port for replay-safe execution of an idempotent unit of work, keyed by a
 * client-supplied {@link IdempotencyKey} and disambiguated by a
 * {@link RequestFingerprint} of the request body.
 *
 * <p>The contract of {@link #executeIdempotent(IdempotencyKey, RequestFingerprint, Supplier)}:
 * <ul>
 *   <li>First occurrence of the key: claim the key, invoke {@code work}, persist its
 *       {@link ResponseRecord} result alongside the key, and return that record.</li>
 *   <li>Replay (same key, same fingerprint, prior result COMPLETED): return the stored
 *       {@link ResponseRecord} without re-invoking {@code work}.</li>
 *   <li>Concurrent in-flight (same key, prior request still PENDING): throw
 *       {@link IdempotencyConflictException}.</li>
 *   <li>Reuse with different body (same key, fingerprint mismatch): throw
 *       {@link IdempotencyKeyReuseException}.</li>
 * </ul>
 *
 * <p>The implementation does NOT manage its own transaction; it relies on the
 * caller's surrounding {@code @Transactional} so that the claim-INSERT and the
 * work's writes commit or roll back together. If {@code work} throws an
 * unclassified exception that escapes the supplier, the surrounding transaction
 * rolls back and the claim row disappears with it — the next attempt sees a
 * fresh key.
 *
 * <p>Plain Java by design: no Spring annotations, no JPA types. The single
 * implementation lives in
 * {@code com.bank.core.infrastructure.idempotency.IdempotencyJpaAdapter}.
 */
public interface IdempotencyStore {

    ResponseRecord executeIdempotent(IdempotencyKey key,
                                     RequestFingerprint fingerprint,
                                     Supplier<ResponseRecord> work);
}
