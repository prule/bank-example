package com.bank.core.service;

import com.bank.core.domain.Account;

public class AccountPair {
  private Account sourceAccount;
  private Account destinationAccount;

  public AccountPair(Account sourceAccount, Account destinationAccount) {
    this.sourceAccount = sourceAccount;
    this.destinationAccount = destinationAccount;
  }

  public Account getSourceAccount() {
    return sourceAccount;
  }

  public Account getDestinationAccount() {
    return destinationAccount;
  }
}
