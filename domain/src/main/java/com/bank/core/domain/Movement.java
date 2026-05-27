package com.bank.core.domain;

import java.util.Objects;

public record Movement(AccountId accountId, Money amount, MovementType type) {
    public Movement {
        Objects.requireNonNull(accountId, "AccountId must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(type, "MovementType must not be null");
        if (amount.isZero() || amount.compareTo(Money.ZERO) < 0) {
            throw new InvalidAmountException(amount);
        }
    }
}
