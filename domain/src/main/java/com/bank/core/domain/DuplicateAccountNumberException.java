package com.bank.core.domain;

/**
 * Exception thrown when an attempt is made to open an account with an already existing account number.
 */
public final class DuplicateAccountNumberException extends DomainException {
    private final String number;

    public DuplicateAccountNumberException(String number) {
        super(String.format("Rejection: Account number already exists in the system: %s", number));
        this.number = number;
    }

    public String number() {
        return number;
    }
}
