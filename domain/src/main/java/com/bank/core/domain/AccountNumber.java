package com.bank.core.domain;

import java.util.Objects;

public record AccountNumber(String value) {

    public AccountNumber {
        Objects.requireNonNull(value, "account number cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account number cannot be blank");
        }
    }

    public static AccountNumber of(String value) {
        return new AccountNumber(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
