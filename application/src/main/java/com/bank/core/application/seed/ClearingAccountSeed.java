package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;

import java.util.Objects;

/**
 * Clearing-account entry in a {@link SeedPlan}. Unlike a {@link CustomerSeed},
 * the opening balance MUST be strictly positive — seeding exists to fund
 * customers, so a clearing account materialised at zero is a configuration
 * error that would silently waste the seeder's purpose.
 *
 * <p>This is the one and only entry in the entire codebase that legitimately
 * causes an account to be persisted at a non-zero balance without a matching
 * funding transfer. Every other credit in the system flows through F06's
 * journal-balanced transfer pipeline.
 */
public record ClearingAccountSeed(AccountNumber number, Money openingBalance) {

    public ClearingAccountSeed {
        Objects.requireNonNull(number, "number cannot be null");
        Objects.requireNonNull(openingBalance, "openingBalance cannot be null");
        if (openingBalance.isZero()) {
            throw new IllegalArgumentException(
                    "clearing-account opening balance must be strictly positive — seeding exists to fund customers");
        }
    }
}
