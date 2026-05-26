package com.bank.core.infrastructure.web.error;

import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.IdempotencyConflictException;
import com.bank.core.domain.IdempotencyKeyReuseException;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.domain.InvalidAmountException;
import com.bank.core.domain.InvalidIdempotencyKeyException;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;
import com.bank.core.dto.ErrorEnvelope;
import com.bank.core.dto.ErrorEnvelope.CodeEnum;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Pure exception → {@code (HttpStatus, ErrorEnvelope)} mapping. No IO, no
 * logging — that's the global exception handler's job. This component exists
 * so two callers can produce the same envelope for the same exception:
 *
 * <ul>
 *   <li>{@link GlobalExceptionHandler}: uses it to produce the live response
 *       to a 4xx error.</li>
 *   <li>{@code TransferController}'s idempotency wrapper: uses it to produce
 *       the envelope it persists alongside the {@code idempotency_key} row,
 *       so a later replay reconstructs the same fields.</li>
 * </ul>
 *
 * <p>Each mapping is one method. New classified-rejection exceptions add one
 * {@code if} branch here and one {@code @ExceptionHandler} method in
 * {@link GlobalExceptionHandler} that delegates here.
 */
@Component
public class ErrorEnvelopeMapper {

    private final Clock clock;

    public ErrorEnvelopeMapper(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Mapping toMapping(Throwable ex) {
        if (ex instanceof InsufficientFundsException) {
            return new Mapping(HttpStatus.BAD_REQUEST, envelope(CodeEnum.INSUFFICIENT_FUNDS,
                    "Source account has insufficient funds for the requested transfer."));
        }
        if (ex instanceof AccountInactiveException aix) {
            return new Mapping(HttpStatus.BAD_REQUEST, envelope(CodeEnum.ACCOUNT_INACTIVE,
                    "Account " + aix.accountId() + " is not Active (status: " + aix.status() + ")."));
        }
        if (ex instanceof InvalidAmountException) {
            return new Mapping(HttpStatus.BAD_REQUEST, envelope(CodeEnum.BAD_REQUEST_PAYLOAD,
                    ex.getMessage()));
        }
        if (ex instanceof SameAccountTransferException) {
            return new Mapping(HttpStatus.BAD_REQUEST, envelope(CodeEnum.BAD_REQUEST_PAYLOAD,
                    ex.getMessage()));
        }
        if (ex instanceof InvalidIdempotencyKeyException) {
            return new Mapping(HttpStatus.BAD_REQUEST, envelope(CodeEnum.BAD_REQUEST_PAYLOAD,
                    ex.getMessage()));
        }
        if (ex instanceof ResourceNotFoundException) {
            return new Mapping(HttpStatus.NOT_FOUND, envelope(CodeEnum.RESOURCE_NOT_FOUND,
                    ex.getMessage()));
        }
        if (ex instanceof IdempotencyConflictException) {
            return new Mapping(HttpStatus.CONFLICT, envelope(CodeEnum.CONCURRENT_IDEMPOTENT_REQUEST,
                    ex.getMessage()));
        }
        if (ex instanceof IdempotencyKeyReuseException) {
            return new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, envelope(CodeEnum.IDEMPOTENCY_KEY_REUSED,
                    ex.getMessage()));
        }
        throw new IllegalArgumentException(
                "ErrorEnvelopeMapper has no mapping for " + ex.getClass().getName()
                        + "; add an explicit case rather than relying on a catch-all");
    }

    private ErrorEnvelope envelope(CodeEnum code, String message) {
        ErrorEnvelope envelope = new ErrorEnvelope();
        envelope.setCode(code);
        envelope.setMessage(message);
        // Truncate to microseconds: the H2 `TIMESTAMP WITH TIME ZONE` column
        // used by the idempotency store has microsecond precision (default
        // TIMESTAMP precision in H2 v2.x). On JVMs where Clock.systemUTC()
        // returns nanosecond precision (Linux Java 25), the in-memory envelope
        // would carry 9-digit fractional seconds but the round-tripped one
        // (read back for a replay) would carry 6, breaking the "first response
        // == replay response" property the transfer-idempotency spec requires.
        // Truncating here guarantees parity for both the storage round-trip
        // and any test that compares the live envelope to a serialised copy.
        envelope.setTimestamp(OffsetDateTime.now(clock)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS));
        return envelope;
    }

    /** Immutable pair: HTTP status + envelope DTO to return to the client. */
    public record Mapping(HttpStatus status, ErrorEnvelope envelope) {
    }
}
