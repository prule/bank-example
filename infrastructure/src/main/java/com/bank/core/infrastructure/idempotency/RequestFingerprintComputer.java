package com.bank.core.infrastructure.idempotency;

import com.bank.core.domain.RequestFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the canonical-JSON SHA-256 fingerprint of a request body. Used by
 * the idempotency store to detect "same key, different body" reuse without
 * persisting the body itself.
 *
 * <h2>Canonicalisation</h2>
 * The input string is parsed as JSON, re-serialised with lexicographically
 * sorted keys at every nesting level and no whitespace, then hashed with
 * SHA-256 and hex-encoded. Two semantically-equal bodies that differ only in
 * whitespace or top-level key order produce the same fingerprint and so do
 * NOT trigger {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Lives in infrastructure (not domain) because canonicalisation needs
 * Jackson, and {@code domain} is framework-free per the existing ArchUnit
 * boundary discipline.
 */
@Component
public class RequestFingerprintComputer {

    private final ObjectMapper canonicalMapper;

    public RequestFingerprintComputer() {
        // ORDER_MAP_ENTRIES_BY_KEYS sorts JsonNode keys on serialisation;
        // combined with INDENT_OUTPUT=false (the default) we get sorted,
        // whitespace-free canonical JSON.
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public RequestFingerprint fingerprintOf(String requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("requestBody cannot be null");
        }
        String canonical = canonicalise(requestBody);
        byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        return RequestFingerprint.ofHex(hex(digest));
    }

    private String canonicalise(String body) {
        try {
            JsonNode parsed = canonicalMapper.readTree(body);
            return canonicalMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("requestBody is not valid JSON", ex);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JDK mandatory algorithm; unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
