package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;

import java.util.Objects;

/**
 * One customer entry in a {@link SeedPlan}. Consumed by {@code SeedData};
 * zero opening balance accepted (matches F08's zero-open scenario); negatives
 * impossible by {@link Money}'s non-negative invariant.
 */
public record CustomerSeed(AccountNumber number, Money openingBalance) {

    public CustomerSeed {
        Objects.requireNonNull(number, "number cannot be null");
        Objects.requireNonNull(openingBalance, "openingBalance cannot be null");
    }
}
