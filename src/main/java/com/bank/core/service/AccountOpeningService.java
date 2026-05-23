package com.bank.core.service;

import com.bank.core.domain.Account;
import com.bank.core.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountOpeningService {

  private final AccountRepository accountRepository;
  private final TransferService transferService;

  public static final String CLEARING_ACCOUNT_NUM = "SYSTEM_CLEARING_001";

  public AccountOpeningService(
      AccountRepository accountRepository,
      TransferService transferService) {
    this.accountRepository = accountRepository;
    this.transferService = transferService;
  }

  @Transactional
  public Account openNewAccount(String accountNumber, BigDecimal initialBalance) {
    // 1. Create the new account with a baseline cache of ZERO
    Account newAccount = new Account(UUID.randomUUID(), accountNumber, BigDecimal.ZERO);
    accountRepository.save(newAccount);

    // 2. If there is an initial balance, fund it cleanly through the ledger
    if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
      Account clearingAccount =
          accountRepository
              .findByAccountNumber(CLEARING_ACCOUNT_NUM)
              .orElseThrow(
                  () -> new IllegalStateException("System clearing account must be pre-seeded!"));

      transferService.transferFunds(
          clearingAccount.getAccountNumber(), newAccount.getAccountNumber(), initialBalance);

    }

    return newAccount;
  }
}
