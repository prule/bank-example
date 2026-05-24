package com.bank.core.domain;

import java.util.Objects;

/**
 * Signals that an account-opening request asked for a positive opening balance
 * but the configured clearing account does not exist. This is a
 * system-misconfiguration signal (e.g. {@code bank.clearing-account.number}
 * points at an account that was never seeded), distinct from a customer-facing
 * 404 — operators monitoring for this class name should see it as an alert,
 * not as a routine rejection.
 *
 * <p>Thrown by {@code com.bank.core.application.account.OpenAccount#open(...)}
 * when the opening balance is positive and the clearing-account row is absent.
 * Opening with a zero balance never trips this precondition; otherwise
 * bootstrapping the clearing account itself would be unreachable.
 */
public final class ClearingAccountMissingException extends DomainException {

    private final AccountNumber clearingAccountNumber;

    public ClearingAccountMissingException(AccountNumber clearingAccountNumber) {
        super("clearing account '"
                + Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber cannot be null").value()
                + "' does not exist — cannot fund a positive opening balance");
        this.clearingAccountNumber = clearingAccountNumber;
    }

    public AccountNumber clearingAccountNumber() {
        return clearingAccountNumber;
    }
}
