package com.bank.core.domain;

/**
 * Exception thrown when a transfer is attempted between the same source and destination account.
 */
public final class SameAccountTransferException extends DomainException {
    private final String account;

    public SameAccountTransferException(String account) {
        super(String.format("Self-transfer is rejected. Source and destination accounts are the same: %s", account));
        this.account = account;
    }

    public String account() {
        return account;
    }
}
