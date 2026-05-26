package com.bank.core.infrastructure.persistence.idempotency;

import com.bank.core.application.idempotency.IdempotencyStore;
import com.bank.core.application.idempotency.ResponseRecord;
import com.bank.core.domain.IdempotencyConflictException;
import com.bank.core.domain.IdempotencyKey;
import com.bank.core.domain.IdempotencyKeyReuseException;
import com.bank.core.domain.RequestFingerprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * JPA-backed {@link IdempotencyStore} implementation. Atomicity is delegated
 * to the database via the {@code idempotency_key} primary-key constraint:
 * the first INSERT to claim a key wins; a concurrent second INSERT with the
 * same key fails the constraint, which we catch and use as the "look up
 * what the winner stored" signal.
 *
 * <h2>No own {@code @Transactional}</h2>
 * Per the {@code transfer-idempotency} design, the caller's transaction
 * wraps the whole flow. This adapter intentionally does NOT annotate
 * {@link #executeIdempotent} so the claim INSERT and the work's writes
 * commit (or roll back) together. {@code SaveAndFlushAfter} semantics are
 * achieved by calling {@code repository.saveAndFlush(...)} so the unique
 * constraint fires at the call site, not at commit.
 *
 * <h2>Pipeline rollback also rolls back the claim</h2>
 * If the work's {@link Supplier#get()} throws (e.g. an unclassified runtime
 * exception), the exception propagates out of this method; the caller's
 * transaction rolls back; the {@code PENDING} row inserted by this method
 * disappears. The next attempt with the same key sees a fresh slate.
 */
@Component
public class IdempotencyJpaAdapter implements IdempotencyStore {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final IdempotencyKeyRepository repository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyJpaAdapter(IdempotencyKeyRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResponseRecord executeIdempotent(IdempotencyKey key,
                                            RequestFingerprint fingerprint,
                                            Supplier<ResponseRecord> work) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(work, "work");

        // Fast path: an existing row trumps INSERT. The lookup is one cheap
        // PK SELECT; the INSERT path on a duplicate would otherwise propagate
        // a Hibernate ConstraintViolationException out of the flush, which is
        // not what the controller wants to see.
        var existing = repository.findById(key.value());
        if (existing.isPresent()) {
            return resolveExisting(key, fingerprint, existing.get());
        }

        IdempotencyKeyEntity claimed = tryClaim(key, fingerprint);
        if (claimed == null) {
            // Race: another request inserted between our findById and persist.
            // Clear the session (the failed persist left junk in it) and
            // re-read; the winner's row is now visible.
            entityManager.clear();
            IdempotencyKeyEntity winner = repository.findById(key.value())
                    .orElseThrow(() -> new IllegalStateException(
                            "PK conflict for key '" + key + "' but row not found on follow-up read"));
            return resolveExisting(key, fingerprint, winner);
        }

        // Won the claim. Run the work; persist its outcome.
        ResponseRecord result = work.get();
        claimed.complete(
                (short) result.httpStatus(),
                result.envelopeCode(),
                result.envelopeMessage(),
                result.envelopeTimestamp());
        repository.save(claimed);
        return result;
    }

    /**
     * Try to claim the key by inserting a PENDING row. Returns the persisted
     * entity on success; returns {@code null} on PK conflict.
     *
     * <p>Uses {@link EntityManager#persist} (not {@code repository.save}) so
     * Hibernate ALWAYS issues an INSERT — never a merge / upsert. With
     * {@code save()} on a new entity that has its PK pre-populated, Spring
     * Data has been observed to perform a find-then-update fallback on
     * replay (the entity "looks" persistent), which silently bypasses the
     * unique-constraint conflict and lets the pipeline run twice. Direct
     * {@code persist} + {@code flush} produces the INSERT we need.
     *
     * <p>The exception types caught here cover both JPA-translated and raw
     * Hibernate flavours: {@link EntityExistsException} is JPA's standard
     * PK-conflict signal; {@link DataIntegrityViolationException} appears
     * when Spring's exception translator has been involved; the
     * {@code org.hibernate.exception.ConstraintViolationException} catch
     * covers direct-EntityManager flushes where Spring's translator is
     * bypassed.
     */
    private IdempotencyKeyEntity tryClaim(IdempotencyKey key, RequestFingerprint fingerprint) {
        IdempotencyKeyEntity candidate = new IdempotencyKeyEntity(
                key.value(),
                fingerprint.hex(),
                STATUS_PENDING,
                (short) 0,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        try {
            entityManager.persist(candidate);
            entityManager.flush();
            return candidate;
        } catch (EntityExistsException | DataIntegrityViolationException
                 | org.hibernate.exception.ConstraintViolationException ex) {
            return null;
        }
    }

    private ResponseRecord resolveExisting(IdempotencyKey key,
                                            RequestFingerprint fingerprint,
                                            IdempotencyKeyEntity existing) {
        if (!existing.getRequestFingerprint().equals(fingerprint.hex())) {
            throw new IdempotencyKeyReuseException(key);
        }

        if (STATUS_PENDING.equals(existing.getStatus())) {
            throw new IdempotencyConflictException(key);
        }

        // COMPLETED with matching fingerprint — replay.
        return new ResponseRecord(
                existing.getHttpStatus(),
                existing.getEnvelopeCode(),
                existing.getEnvelopeMessage(),
                existing.getEnvelopeTimestamp());
    }
}
