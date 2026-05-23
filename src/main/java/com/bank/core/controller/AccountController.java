package com.bank.core.controller;

import com.bank.core.api.AccountsApi;
import com.bank.core.domain.Account;
import com.bank.core.dto.AccountResponse;
import com.bank.core.repository.AccountRepository;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController implements AccountsApi {

  private final AccountRepository accountRepository;

  // Standard constructor injection for your database layer
  public AccountController(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * GET /api/v1/accounts/{accountNumber} Implements the exact method signature defined in your
   * split YAML spec.
   */
  @Override
  @Transactional(readOnly = true)
  public ResponseEntity<AccountResponse> getAccountByNumber(String accountNumber) {

    // 1. Fetch domain model from database
    Account account =
        accountRepository
            .findByAccountNumber(accountNumber)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Account '" + accountNumber + "' not found"));

    // 2. Map domain model cleanly to the generated OpenAPI DTO contract
    AccountResponse response = new AccountResponse();
    response.setAccountNumber(account.getAccountNumber());
    response.setBalance(account.getBalance().doubleValue()); // Safely convert BigDecimal to Double

    // Match the enum values exactly (ACTIVE, SUSPENDED)
    response.setStatus(AccountResponse.StatusEnum.valueOf(account.getStatus().name()));

    return ResponseEntity.ok(response);
  }
}
