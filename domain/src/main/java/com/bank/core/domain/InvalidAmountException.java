package com.bank.core.domain;

import java.math.BigDecimal;

public final class InvalidAmountException extends DomainException {
    private final Money attemptedAmount;

    public InvalidAmountException(Money attemptedAmount) {
        super(String.format("Invalid transaction amount: %s. Amount must be strictly positive.", attemptedAmount));
        this.attemptedAmount = attemptedAmount;
    }

    public InvalidAmountException(BigDecimal attemptedAmount) {
        super(String.format("Invalid transaction amount: %s. Amount must be strictly positive.", attemptedAmount));
        this.attemptedAmount = attemptedAmount != null && attemptedAmount.compareTo(BigDecimal.ZERO) >= 0 ? Money.of(attemptedAmount) : null;
    }

    public Money getAttemptedAmount() {
        return attemptedAmount;
    }
}
