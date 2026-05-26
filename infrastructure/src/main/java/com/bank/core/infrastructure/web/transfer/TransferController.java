package com.bank.core.infrastructure.web.transfer;

import com.bank.core.api.TransfersApi;
import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.dto.TransferRequest;
import com.bank.core.infrastructure.observability.TransferMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sole transactional HTTP controller in this branch. F06 owns the only
 * write endpoint that orchestrates multiple aggregate writes inside F07's
 * paired locks; the {@link Transactional} annotation on
 * {@link #createTransfer(TransferRequest)} is the explicit place where the
 * transaction-scoped lifecycle starts and ends.
 *
 * Why on the controller and not the use case: the use case
 * ({@code com.bank.core.application.transfer.TransferFunds}) is plain Java
 * to honour F02's {@code transactional-in-application} precedent (no Spring
 * in {@code application} production sources). The next-best home for the
 * boundary is the request-scoped controller method. F05's read controller
 * does not need {@code @Transactional} because its adapter manages
 * {@code @Transactional(readOnly = true)} internally.
 */
@RestController
public class TransferController implements TransfersApi {

    private final TransferMetrics transferMetrics;
    private final TransferRequestMapper mapper;

    // Depends on TransferMetrics rather than TransferFunds directly so every
    // transfer attempt is timed and classified by outcome (bank.transfer.*).
    // TransferMetrics delegates to the framework-free TransferFunds use case
    // and re-throws every exception so error-handling and rollback semantics
    // stay unchanged.
    public TransferController(TransferMetrics transferMetrics, TransferRequestMapper mapper) {
        this.transferMetrics = transferMetrics;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ResponseEntity<Void> createTransfer(TransferRequest request) {
        TransferCommand command = mapper.toCommand(request);
        transferMetrics.transfer(command);
        return ResponseEntity.noContent().build();
    }
}
