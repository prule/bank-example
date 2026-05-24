package com.bank.core.domain;

public final class InsufficientFundsException extends DomainException {

    private final AccountId accountId;
    private final Money attempted;
    private final Money available;

    public InsufficientFundsException(AccountId accountId, Money attempted, Money available) {
        super("Account " + accountId + " has balance " + available + " but debit attempted " + attempted);
        this.accountId = accountId;
        this.attempted = attempted;
        this.available = available;
    }

    public AccountId accountId() {
        return accountId;
    }

    public Money attempted() {
        return attempted;
    }

    public Money available() {
        return available;
    }
}
