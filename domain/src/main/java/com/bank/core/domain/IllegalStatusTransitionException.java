package com.bank.core.domain;

public final class IllegalStatusTransitionException extends DomainException {
    private final AccountId accountId;
    private final AccountStatus currentStatus;
    private final AccountStatus targetStatus;

    public IllegalStatusTransitionException(AccountId accountId, AccountStatus currentStatus, AccountStatus targetStatus) {
        super(String.format("Illegal status transition for account %s: %s -> %s", accountId, currentStatus, targetStatus));
        this.accountId = accountId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public AccountStatus getCurrentStatus() {
        return currentStatus;
    }

    public AccountStatus getTargetStatus() {
        return targetStatus;
    }
}
