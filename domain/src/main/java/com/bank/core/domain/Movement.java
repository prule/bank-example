package com.bank.core.domain;

import java.util.Objects;

public record Movement(AccountId accountId, Money amount, MovementType type) {

    public Movement {
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        if (amount.isZero()) {
            throw new InvalidAmountException("movement amount must be positive");
        }
    }
}
