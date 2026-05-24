package com.bank.core.domain;

public final class IllegalStatusTransitionException extends DomainException {

    private final AccountId accountId;
    private final AccountStatus from;
    private final AccountStatus to;

    public IllegalStatusTransitionException(AccountId accountId, AccountStatus from, AccountStatus to) {
        super("Account " + accountId + " cannot transition from " + from + " to " + to);
        this.accountId = accountId;
        this.from = from;
        this.to = to;
    }

    public AccountId accountId() {
        return accountId;
    }

    public AccountStatus from() {
        return from;
    }

    public AccountStatus to() {
        return to;
    }
}
