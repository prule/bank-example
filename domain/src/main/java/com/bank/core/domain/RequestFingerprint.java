package com.bank.core.domain;

import java.util.Objects;

/**
 * Hex-encoded SHA-256 fingerprint of the canonical JSON encoding of a request
 * body. Used by the idempotency store to detect "same key, different body"
 * reuse without storing the request body itself.
 *
 * <p>The fingerprint is a 64-character lowercase hex string (256 bits of
 * SHA-256 → 64 hex chars). The value object itself only validates the shape;
 * the actual canonicalisation + hashing lives in infrastructure where Jackson
 * is available — see {@code RequestFingerprintComputer}.
 */
public final class RequestFingerprint {

    /** SHA-256 hex length: 256 bits / 4 bits-per-hex-char = 64. */
    public static final int HEX_LENGTH = 64;

    private final String hex;

    private RequestFingerprint(String hex) {
        this.hex = hex;
    }

    public static RequestFingerprint ofHex(String hex) {
        if (hex == null || hex.length() != HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "RequestFingerprint hex must be exactly " + HEX_LENGTH + " characters");
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean valid = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!valid) {
                throw new IllegalArgumentException(
                        "RequestFingerprint hex must be lowercase 0-9 a-f (rejected at index " + i + ")");
            }
        }
        return new RequestFingerprint(hex);
    }

    public String hex() {
        return hex;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RequestFingerprint other && Objects.equals(this.hex, other.hex);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(hex);
    }

    @Override
    public String toString() {
        return hex;
    }
}
