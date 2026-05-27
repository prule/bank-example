package com.bank.core.application.account;

import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;

import java.util.Optional;

public interface Accounts {
    Optional<Account> findByNumber(String number);
    Optional<Account> findById(AccountId id);
    Account save(Account account);
}
