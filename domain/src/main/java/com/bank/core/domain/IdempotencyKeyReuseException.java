package com.bank.core.domain;

/**
 * Thrown when an {@code Idempotency-Key} has been seen before with a
 * different request body (the stored {@code request_fingerprint} does not
 * match the incoming request's). Mapped by the HTTP error contract to 422
 * {@code IDEMPOTENCY_KEY_REUSED}; the typical cause is a client bug.
 */
public final class IdempotencyKeyReuseException extends DomainException {

    private final IdempotencyKey key;

    public IdempotencyKeyReuseException(IdempotencyKey key) {
        super("Idempotency-Key '" + key + "' was previously used with a different request body.");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
