package com.bank.core.infrastructure.web.transfer;

import com.bank.core.api.TransfersApi;
import com.bank.core.application.idempotency.IdempotencyStore;
import com.bank.core.application.idempotency.ResponseRecord;
import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.domain.IdempotencyKey;
import com.bank.core.domain.RequestFingerprint;
import com.bank.core.dto.ErrorEnvelope;
import com.bank.core.dto.ErrorEnvelope.CodeEnum;
import com.bank.core.dto.TransferRequest;
import com.bank.core.infrastructure.idempotency.RequestFingerprintComputer;
import com.bank.core.infrastructure.observability.TransferMetrics;
import com.bank.core.infrastructure.web.error.ErrorEnvelopeMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sole transactional HTTP controller in this branch. F06 owns the only
 * write endpoint that orchestrates multiple aggregate writes inside F07's
 * paired locks; the {@link Transactional} annotation on
 * {@link #createTransfer(TransferRequest, String)} is the explicit place
 * where the transaction-scoped lifecycle starts and ends.
 *
 * <h2>Idempotency branch</h2>
 * When the optional {@code Idempotency-Key} header is supplied, the request
 * runs through {@link IdempotencyStore#executeIdempotent}: first occurrence
 * runs the pipeline AND persists its response in the same transaction;
 * replays return the persisted response without re-running the pipeline.
 * When absent, the request behaves exactly as without this capability.
 *
 * <h2>Why classified exceptions are caught inside the supplier</h2>
 * The pipeline's classified-rejection exceptions (e.g. InsufficientFunds,
 * AccountInactive, ResourceNotFound) map to deterministic 4xx responses.
 * The idempotency contract requires those responses to be PERSISTED so
 * future replays return the same answer. Letting the exceptions escape the
 * supplier would roll back the surrounding transaction — including the
 * persistence write — leaving nothing to replay. So the supplier catches
 * them, asks {@link ErrorEnvelopeMapper} for the envelope, returns a
 * {@link ResponseRecord} that gets persisted. UNCLASSIFIED exceptions
 * (lock timeout, infrastructure failure) escape normally; the transaction
 * rolls back and the next attempt with the same key is treated as fresh.
 */
@RestController
public class TransferController implements TransfersApi {

    private final TransferMetrics transferMetrics;
    private final TransferRequestMapper mapper;
    private final IdempotencyStore idempotencyStore;
    private final RequestFingerprintComputer fingerprintComputer;
    private final ErrorEnvelopeMapper errorEnvelopeMapper;
    private final ObjectMapper objectMapper;

    public TransferController(TransferMetrics transferMetrics,
                              TransferRequestMapper mapper,
                              IdempotencyStore idempotencyStore,
                              RequestFingerprintComputer fingerprintComputer,
                              ErrorEnvelopeMapper errorEnvelopeMapper,
                              ObjectMapper objectMapper) {
        this.transferMetrics = transferMetrics;
        this.mapper = mapper;
        this.idempotencyStore = idempotencyStore;
        this.fingerprintComputer = fingerprintComputer;
        this.errorEnvelopeMapper = errorEnvelopeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ResponseEntity<Void> createTransfer(TransferRequest request, String idempotencyKey) {
        TransferCommand command = mapper.toCommand(request);
        if (idempotencyKey == null) {
            transferMetrics.transfer(command);
            return ResponseEntity.noContent().build();
        }

        IdempotencyKey key = IdempotencyKey.of(idempotencyKey);
        RequestFingerprint fingerprint = fingerprintComputer.fingerprintOf(serialise(request));

        ResponseRecord result = idempotencyStore.executeIdempotent(key, fingerprint, () -> runAndCapture(command));
        return toResponseEntity(result);
    }

    private ResponseRecord runAndCapture(TransferCommand command) {
        try {
            transferMetrics.transfer(command);
            return ResponseRecord.success();
        } catch (RuntimeException ex) {
            ErrorEnvelopeMapper.Mapping mapping;
            try {
                mapping = errorEnvelopeMapper.toMapping(ex);
            } catch (IllegalArgumentException unmapped) {
                // Not a classified rejection (e.g. LockAcquisitionTimeoutException,
                // an infrastructure RuntimeException). Let it propagate so the
                // surrounding @Transactional rolls back the PENDING idempotency
                // row and the next retry is treated as fresh.
                throw ex;
            }
            ErrorEnvelope env = mapping.envelope();
            return ResponseRecord.rejection(
                    mapping.status().value(),
                    env.getCode().getValue(),
                    env.getMessage(),
                    env.getTimestamp());
        }
    }

    private String serialise(TransferRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to canonicalise TransferRequest for fingerprint", ex);
        }
    }

    // Returns either an empty 204 or a 4xx with an ErrorEnvelope body.
    // The TransfersApi-generated method declares ResponseEntity<Void>; this
    // unchecked cast is safe at runtime because Java generics are erased and
    // Spring's HttpMessageConverter inspects the actual body object — so a
    // ResponseEntity carrying an ErrorEnvelope serialises correctly regardless
    // of the declared <Void>. Documented here so a reader doesn't worry.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Void> toResponseEntity(ResponseRecord r) {
        if (r.isSuccess()) {
            return ResponseEntity.noContent().build();
        }
        ErrorEnvelope envelope = new ErrorEnvelope();
        envelope.setCode(CodeEnum.fromValue(r.envelopeCode()));
        envelope.setMessage(r.envelopeMessage());
        envelope.setTimestamp(r.envelopeTimestamp());
        ResponseEntity reply = ResponseEntity.status(r.httpStatus()).body(envelope);
        return (ResponseEntity<Void>) reply;
    }
}
