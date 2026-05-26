package com.bank.core.application.idempotency;

import java.time.OffsetDateTime;

/**
 * Immutable record of the response produced by a {@code POST /api/v1/transfers}
 * invocation, suitable for persistence in the {@code idempotency_key} table
 * and for reconstruction on replay.
 *
 * <p>For a successful transfer ({@code 204 No Content}), {@code httpStatus}
 * is {@code 204} and all envelope-* fields are {@code null}.
 *
 * <p>For a classified-rejection response (4xx), {@code httpStatus} is the
 * HTTP status and the three envelope fields together reconstruct the
 * {@code ErrorEnvelope} DTO byte-stably (the generated DTO's
 * {@code @JsonProperty} annotations pin field order; the captured timestamp
 * preserves the original first-response value).
 */
public record ResponseRecord(int httpStatus,
                             String envelopeCode,
                             String envelopeMessage,
                             OffsetDateTime envelopeTimestamp) {

    /** Factory for the 204 success response. */
    public static ResponseRecord success() {
        return new ResponseRecord(204, null, null, null);
    }

    /** Factory for a classified-rejection 4xx response carrying an envelope. */
    public static ResponseRecord rejection(int httpStatus,
                                           String envelopeCode,
                                           String envelopeMessage,
                                           OffsetDateTime envelopeTimestamp) {
        return new ResponseRecord(httpStatus, envelopeCode, envelopeMessage, envelopeTimestamp);
    }

    public boolean isSuccess() {
        return httpStatus == 204;
    }
}
