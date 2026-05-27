package com.bank.core.infrastructure.account;

import com.bank.core.application.account.OpenAccount;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Infrastructure service facade providing transactional wrapper for opening new accounts.
 */
@Service
@Transactional
public class OpenAccountService {
    private final OpenAccount openAccount;

    public OpenAccountService(OpenAccount openAccount) {
        this.openAccount = Objects.requireNonNull(openAccount, "OpenAccount use case must not be null");
    }

    /**
     * Executes the customer account opening operation atomically inside a Spring-managed transaction.
     *
     * @param command the account opening request command
     * @return the reloaded Account aggregate reflecting the committed state
     */
    public Account open(OpenAccountCommand command) {
        return openAccount.open(command);
    }
}
