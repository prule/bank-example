package com.bank.core.domain;

public final class AccountInactiveException extends DomainException {

    private final AccountId accountId;
    private final AccountStatus status;

    public AccountInactiveException(AccountId accountId, AccountStatus status) {
        super("Account " + accountId + " is not Active (status: " + status + ")");
        this.accountId = accountId;
        this.status = status;
    }

    public AccountId accountId() {
        return accountId;
    }

    public AccountStatus status() {
        return status;
    }
}
