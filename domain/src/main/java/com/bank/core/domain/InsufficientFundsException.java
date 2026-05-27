package com.bank.core.domain;

public final class InsufficientFundsException extends DomainException {
    private final AccountId accountId;
    private final Money attemptedAmount;
    private final Money currentBalance;

    public InsufficientFundsException(AccountId accountId, Money attemptedAmount, Money currentBalance) {
        super(String.format("Account %s has insufficient funds to debit %s (current balance: %s)",
                accountId, attemptedAmount, currentBalance));
        this.accountId = accountId;
        this.attemptedAmount = attemptedAmount;
        this.currentBalance = currentBalance;
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public Money getAttemptedAmount() {
        return attemptedAmount;
    }

    public Money getCurrentBalance() {
        return currentBalance;
    }
}
