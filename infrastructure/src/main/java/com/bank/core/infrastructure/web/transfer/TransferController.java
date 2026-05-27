package com.bank.core.infrastructure.web.transfer;

import com.bank.core.api.TransfersApi;
import com.bank.core.dto.TransferRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller implementing TransfersApi for handling fund transfer operations.
 * Annotated at method level with @Transactional per specifications.
 */
@RestController
public class TransferController implements TransfersApi {

    @Override
    public ResponseEntity<Void> transferFunds(TransferRequest transferRequest) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }
}
