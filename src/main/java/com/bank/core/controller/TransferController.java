package com.bank.core.controller;

import com.bank.core.api.TransfersApi;
import com.bank.core.dto.TransferRequest;
import com.bank.core.service.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class TransferController implements TransfersApi {

  private final TransferService transferService;

  // Constructor injection - no field @Autowired allowed for senior engineering roles
  public TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  @Override
  public ResponseEntity<Void> transferFunds(TransferRequest transferRequest) {
    BigDecimal amount = BigDecimal.valueOf(transferRequest.getAmount());

    transferService.transferFunds(
        transferRequest.getSourceAccountNumber(),
        transferRequest.getDestinationAccountNumber(),
        amount);

    return ResponseEntity.noContent().build();
  }
}
