package com.bank.core.domain;

/**
 * Exception thrown when a positive opening balance transfer is attempted but the bank's configured clearing account does not exist.
 */
public final class ClearingAccountMissingException extends DomainException {
    private final String clearingAccountNumber;

    public ClearingAccountMissingException(String clearingAccountNumber) {
        super(String.format("Configured internal clearing account is missing: %s", clearingAccountNumber));
        this.clearingAccountNumber = clearingAccountNumber;
    }

    public String clearingAccountNumber() {
        return clearingAccountNumber;
    }
}
