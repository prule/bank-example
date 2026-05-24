package com.bank.core.domain;

import java.util.Objects;

/**
 * Customer or bank-owned account. The aggregate owns its identity (final),
 * balance and status (private mutable), and exposes exactly four mutators:
 * {@link #credit(Money)}, {@link #debit(Money)}, {@link #suspend()},
 * {@link #reactivate()}. Identity comparison is by reference within a single
 * transaction; cross-transaction comparison uses {@code id().equals(...)}.
 *
 * Forward-compatibility notes:
 * <ul>
 *   <li>F02 (immutable ledger) will add a package-private constructor for
 *       persistence rehydration, accepting status as a parameter.</li>
 *   <li>F06 (fund transfer) will call {@code debit}/{@code credit} from a
 *       use-case in {@code application}, inside the locking strategy from F07.</li>
 *   <li>F11 (balance drift detection) will call {@link #suspend()} when
 *       a non-clearing account's cached balance disagrees with its ledger.</li>
 * </ul>
 */
public final class Account {

    private final AccountId id;
    private final AccountNumber number;
    private Money balance;
    private AccountStatus status;

    private Account(AccountId id, AccountNumber number, Money balance, AccountStatus status) {
        this.id = id;
        this.number = number;
        this.balance = balance;
        this.status = status;
    }

    public static Account open(AccountNumber number, Money openingBalance) {
        Objects.requireNonNull(number, "account number cannot be null");
        Objects.requireNonNull(openingBalance, "opening balance cannot be null");
        return new Account(AccountId.generate(), number, openingBalance, AccountStatus.ACTIVE);
    }

    public AccountId id() {
        return id;
    }

    public AccountNumber number() {
        return number;
    }

    public Money balance() {
        return balance;
    }

    public AccountStatus status() {
        return status;
    }

    public void credit(Money amount) {
        requireActive();
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    public void debit(Money amount) {
        requireActive();
        requirePositive(amount);
        if (!this.balance.isGreaterThan(amount)) {
            throw new InsufficientFundsException(id, amount, balance);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void suspend() {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStatusTransitionException(id, status, AccountStatus.SUSPENDED);
        }
        this.status = AccountStatus.SUSPENDED;
    }

    public void reactivate() {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStatusTransitionException(id, status, AccountStatus.ACTIVE);
        }
        this.status = AccountStatus.ACTIVE;
    }

    private void requireActive() {
        if (!status.isActive()) {
            throw new AccountInactiveException(id, status);
        }
    }

    private void requirePositive(Money amount) {
        if (amount == null) {
            throw new InvalidAmountException("amount cannot be null");
        }
        if (amount.isZero()) {
            throw new InvalidAmountException("amount must be positive (was zero)");
        }
    }
}
