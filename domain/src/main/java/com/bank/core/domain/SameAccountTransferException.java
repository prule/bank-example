package com.bank.core.domain;

import java.util.Objects;

/**
 * Signals that a fund-transfer request named the same account as both source
 * and destination. Closes the {@code self-transfer} open decision in
 * {@code openspec/config.yaml} in favour of rejecting the request rather than
 * allowing a no-op transfer or a self-debit/self-credit journal entry.
 */
public final class SameAccountTransferException extends DomainException {

    private final AccountNumber account;

    public SameAccountTransferException(AccountNumber account) {
        super("source and destination must differ (both were '"
                + Objects.requireNonNull(account, "account cannot be null") + "')");
        this.account = account;
    }

    public AccountNumber account() {
        return account;
    }
}
