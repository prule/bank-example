package com.bank.core.infrastructure.web.transfer;

import com.bank.core.api.TransfersApi;
import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Money;
import com.bank.core.dto.TransferRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller implementing TransfersApi for handling fund transfer operations.
 * Annotated at method level with @Transactional per specifications.
 */
@RestController
public class TransferController implements TransfersApi {

    private final TransferFunds transferFunds;

    public TransferController(TransferFunds transferFunds) {
        this.transferFunds = transferFunds;
    }

    /**
     * Executes a fund transfer operation within a Spring-managed transaction boundary.
     * Maps the incoming OpenAPI TransferRequest DTO to the domain TransferCommand.
     */
    @Override
    @Transactional
    public ResponseEntity<Void> transferFunds(TransferRequest transferRequest) {
        TransferCommand command = new TransferCommand(
                transferRequest.getSourceAccountNumber(),
                transferRequest.getDestinationAccountNumber(),
                Money.of(transferRequest.getAmount())
        );

        transferFunds.transfer(command);

        return ResponseEntity.noContent().build();
    }
}
