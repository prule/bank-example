package com.bank.core.infrastructure.persistence.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * JPA mapping for the {@code idempotency_key} table created by
 * {@code V5__idempotency_key.sql}. Holds one row per {@code Idempotency-Key}
 * header value the service has ever seen on {@code POST /api/v1/transfers}.
 *
 * <h2>Field mutability</h2>
 * The first five fields ({@code key}, {@code requestFingerprint},
 * {@code httpStatus} when set to its PENDING-placeholder, {@code createdAt},
 * and the three envelope columns) are written exactly twice per row:
 * <ol>
 *   <li>On INSERT with {@code status=PENDING}.</li>
 *   <li>On UPDATE with {@code status=COMPLETED} and the actual response data.</li>
 * </ol>
 * No further writes happen — the row is read-only after COMPLETED.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "key_value", length = 200, nullable = false, updatable = false)
    private String key;

    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "http_status", nullable = false)
    private short httpStatus;

    @Column(name = "envelope_code", length = 64)
    private String envelopeCode;

    @Column(name = "envelope_message", length = 2000)
    private String envelopeMessage;

    @Column(name = "envelope_timestamp")
    private OffsetDateTime envelopeTimestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKeyEntity() {
        // JPA
    }

    public IdempotencyKeyEntity(String key,
                                String requestFingerprint,
                                String status,
                                short httpStatus,
                                OffsetDateTime createdAt) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.status = status;
        this.httpStatus = httpStatus;
        this.createdAt = createdAt;
    }

    public String getKey() {
        return key;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getStatus() {
        return status;
    }

    public short getHttpStatus() {
        return httpStatus;
    }

    public String getEnvelopeCode() {
        return envelopeCode;
    }

    public String getEnvelopeMessage() {
        return envelopeMessage;
    }

    public OffsetDateTime getEnvelopeTimestamp() {
        return envelopeTimestamp;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Transition this row from {@code PENDING} to {@code COMPLETED} with the
     * given response. The first-execution path calls this exactly once; the
     * row is read-only thereafter.
     */
    public void complete(short httpStatus,
                         String envelopeCode,
                         String envelopeMessage,
                         OffsetDateTime envelopeTimestamp) {
        this.status = "COMPLETED";
        this.httpStatus = httpStatus;
        this.envelopeCode = envelopeCode;
        this.envelopeMessage = envelopeMessage;
        this.envelopeTimestamp = envelopeTimestamp;
    }
}
