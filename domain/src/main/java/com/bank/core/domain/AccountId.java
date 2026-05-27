package com.bank.core.domain;

import java.util.Objects;
import java.util.UUID;

public final class AccountId {
    private final UUID value;

    public AccountId(UUID value) {
        this.value = Objects.requireNonNull(value, "Value must not be null");
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId fromString(String str) {
        Objects.requireNonNull(str, "String must not be null");
        return new AccountId(UUID.fromString(str));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountId)) return false;
        AccountId accountId = (AccountId) o;
        return Objects.equals(value, accountId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
