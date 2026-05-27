package com.bank.core.application.account;

import com.bank.core.domain.Money;
import java.util.Objects;

/**
 * Command representing a request to open a new customer account, optionally funded with an opening balance.
 */
public record OpenAccountCommand(String number, Money openingBalance) {
    public OpenAccountCommand {
        Objects.requireNonNull(number, "number must not be null");
        Objects.requireNonNull(openingBalance, "openingBalance must not be null");
        if (number.trim().isEmpty()) {
            throw new IllegalArgumentException("Account number must not be empty");
        }
    }
}
