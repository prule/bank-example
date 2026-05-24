package com.bank.core.domain;

public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}
