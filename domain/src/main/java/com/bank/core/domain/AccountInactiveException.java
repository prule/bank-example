package com.bank.core.domain;

public final class AccountInactiveException extends DomainException {
    private final AccountId accountId;
    private final AccountStatus status;

    public AccountInactiveException(AccountId accountId, AccountStatus status) {
        super(String.format("Account %s is inactive (current status: %s)", accountId, status));
        this.accountId = accountId;
        this.status = status;
    }

    public AccountId getAccountId() {
        return accountId;
    }

    public AccountStatus getStatus() {
        return status;
    }
}
