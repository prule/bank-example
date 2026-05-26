package com.bank.core.domain;

/**
 * Thrown when an {@code Idempotency-Key} header value violates the format
 * rules (null, empty, &gt; 200 chars, or contains a non-printable-ASCII byte).
 * Mapped by the HTTP error contract to 400 {@code BAD_REQUEST_PAYLOAD}.
 */
public final class InvalidIdempotencyKeyException extends DomainException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
