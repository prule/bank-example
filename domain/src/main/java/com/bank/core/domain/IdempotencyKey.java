package com.bank.core.domain;

import java.util.Objects;

/**
 * Client-supplied {@code Idempotency-Key} header value. 1..200 ASCII
 * characters; rejected at the value-object boundary via
 * {@link InvalidIdempotencyKeyException} so any later layer can assume
 * a validated key.
 *
 * <p>Plain value object: no framework, no IO. The hex-string fingerprint
 * computation that pairs with this key lives in
 * {@code com.bank.core.infrastructure.idempotency.RequestFingerprintComputer}
 * (it needs Jackson; this module is framework-free).
 */
public final class IdempotencyKey {

    /** Length bound matching the {@code key VARCHAR(200)} column. */
    public static final int MAX_LENGTH = 200;

    private final String value;

    private IdempotencyKey(String value) {
        this.value = value;
    }

    public static IdempotencyKey of(String value) {
        if (value == null) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key cannot be null");
        }
        if (value.isEmpty()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key cannot be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key cannot exceed " + MAX_LENGTH + " characters (was " + value.length() + ")");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new InvalidIdempotencyKeyException(
                        "Idempotency-Key must contain only printable ASCII characters (rejected at index " + i + ")");
            }
        }
        return new IdempotencyKey(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IdempotencyKey other && Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
