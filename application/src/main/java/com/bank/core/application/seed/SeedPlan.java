package com.bank.core.application.seed;

import java.util.List;
import java.util.Objects;

/**
 * Immutable description of what F09 should materialise on a fresh database:
 * one clearing account followed by an ordered list of customer accounts.
 * Consumed by {@code SeedData}; constructed in the bootstrap layer from
 * externalised {@code bank.seed.*} configuration so domain-level validation
 * (positive money, non-blank numbers) happens here at bean-creation time.
 *
 * <p>The customer list is defensively copied so post-construction mutation
 * of the source list cannot affect the plan a running seeder is iterating.
 */
public record SeedPlan(ClearingAccountSeed clearingAccount, List<CustomerSeed> customers) {

    public SeedPlan {
        Objects.requireNonNull(clearingAccount, "clearingAccount cannot be null");
        Objects.requireNonNull(customers, "customers cannot be null");
        customers = List.copyOf(customers);
    }
}
