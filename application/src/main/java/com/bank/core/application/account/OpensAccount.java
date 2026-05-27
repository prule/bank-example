package com.bank.core.application.account;

import com.bank.core.domain.Account;

/**
 * Functional interface decoupling callers (such as startup seed runners) from direct
 * dependency on infrastructure facades or specific Spring-bound transactional services.
 */
public interface OpensAccount {
    Account open(OpenAccountCommand command);
}
