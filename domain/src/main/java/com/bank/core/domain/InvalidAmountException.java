package com.bank.core.domain;

public final class InvalidAmountException extends DomainException {

    private final String reason;

    public InvalidAmountException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
