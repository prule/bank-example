package com.bank.core.domain;

/**
 * Thrown when an {@code Idempotency-Key} is currently in flight on another
 * request (the existing row has {@code status=PENDING}). Mapped by the HTTP
 * error contract to 409 {@code CONCURRENT_IDEMPOTENT_REQUEST}; clients
 * should retry after a brief delay.
 */
public final class IdempotencyConflictException extends DomainException {

    private final IdempotencyKey key;

    public IdempotencyConflictException(IdempotencyKey key) {
        super("Another request with Idempotency-Key '" + key + "' is still in flight. Retry shortly.");
        this.key = key;
    }

    public IdempotencyKey key() {
        return key;
    }
}
