package com.bank.core.domain;

import java.util.Objects;

/**
 * Signals that an account-opening request named an {@link AccountNumber} that
 * already maps to a persisted account. Thrown by
 * {@code com.bank.core.application.account.OpenAccount#open(...)} after its
 * duplicate pre-check via the {@code Accounts} port; the F05 unique index on
 * {@code account.account_number} remains the concurrent-write safety net.
 */
public final class DuplicateAccountNumberException extends DomainException {

    private final AccountNumber number;

    public DuplicateAccountNumberException(AccountNumber number) {
        super("account '"
                + Objects.requireNonNull(number, "number cannot be null").value()
                + "' already exists");
        this.number = number;
    }

    public AccountNumber number() {
        return number;
    }
}
