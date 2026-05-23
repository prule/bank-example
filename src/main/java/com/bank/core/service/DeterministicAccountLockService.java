package com.bank.core.service;

import com.bank.core.domain.Account;
import com.bank.core.repository.AccountRepository;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

/** To avoid deadlocks this service loads accounts with lock in a deterministic order. */
@Service
public class DeterministicAccountLockService {

  private final AccountRepository accountRepository;

  public DeterministicAccountLockService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  public AccountPair lock(String sourceAccountNumber, String destinationAccountNumber) {
    Account sourceAccount;
    Account destinationAccount;

    if (sourceAccountNumber.compareTo(destinationAccountNumber) < 0) {
      sourceAccount = loadAccountWithLock(sourceAccountNumber);
      destinationAccount = loadAccountWithLock(destinationAccountNumber);
    } else {
      destinationAccount = loadAccountWithLock(destinationAccountNumber);
      sourceAccount = loadAccountWithLock(sourceAccountNumber);
    }

    return new AccountPair(sourceAccount, destinationAccount);
  }

  private Account loadAccountWithLock(String accountNumber) {
    return accountRepository
        .findByAccountNumberForUpdate(accountNumber)
        .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountNumber));
  }
}
