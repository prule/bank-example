package com.bank.core.domain;

import java.util.Objects;

public final class Account {
    private final AccountId id;
    private final String number;
    private Money balance;
    private AccountStatus status;

    private Account(AccountId id, String number, Money balance, AccountStatus status) {
        this.id = Objects.requireNonNull(id, "Account ID must not be null");
        this.number = Objects.requireNonNull(number, "Account number must not be null");
        if (number.trim().isEmpty()) {
            throw new IllegalArgumentException("Account number must not be empty");
        }
        this.balance = Objects.requireNonNull(balance, "Balance must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
    }

    public static Account open(String accountNumber, Money initialBalance) {
        return new Account(AccountId.generate(), accountNumber, initialBalance, AccountStatus.ACTIVE);
    }

    public void credit(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(id, status);
        }
        if (amount == null || amount.isZero()) {
            throw new InvalidAmountException(amount);
        }
        this.balance = this.balance.plus(amount);
    }

    public void debit(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(id, status);
        }
        if (amount == null || amount.isZero()) {
            throw new InvalidAmountException(amount);
        }
        if (this.balance.compareTo(amount) <= 0) {
            throw new InsufficientFundsException(id, amount, balance);
        }
        this.balance = this.balance.minus(amount);
    }

    public void suspend() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStatusTransitionException(id, status, AccountStatus.SUSPENDED);
        }
        this.status = AccountStatus.SUSPENDED;
    }

    public void reactivate() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStatusTransitionException(id, status, AccountStatus.ACTIVE);
        }
        this.status = AccountStatus.ACTIVE;
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    public AccountId getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Money getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /**
     * Rehydrates an Account aggregate from persisted state.
     * <p>
     * WARNING: This factory is strictly for the persistence mapper
     * ({@code com.bank.core.infrastructure.persistence.account.AccountMapper})
     * and MUST NOT be called by application services or controller code.
     * Bypasses the aggregate creation lifecycle invariants.
     */
    public static Account rehydrate(AccountId id, String number, Money balance, AccountStatus status) {
        return new Account(
            Objects.requireNonNull(id, "Account ID must not be null"),
            Objects.requireNonNull(number, "Account number must not be null"),
            Objects.requireNonNull(balance, "Balance must not be null"),
            Objects.requireNonNull(status, "Status must not be null")
        );
    }
}
