package com.bank.core.application.seed;

import com.bank.core.domain.AccountNumber;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a {@code SeedData.seed()} invocation. Consumed by
 * {@code SeedDataRunner} for log routing (one INFO line per outcome) and by
 * tests asserting which branch the seeder took.
 *
 * <p>{@link Seeded} reports a fresh-DB pass that committed the clearing
 * account plus every customer in the plan in order. {@link Skipped} reports
 * the idempotent no-op branch — the clearing-account pre-check found the
 * row already present and no further work was done.
 */
public sealed interface SeedReport permits SeedReport.Seeded, SeedReport.Skipped {

    record Seeded(AccountNumber clearingAccountNumber,
                  List<AccountNumber> customerAccountNumbers) implements SeedReport {

        public Seeded {
            Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber cannot be null");
            Objects.requireNonNull(customerAccountNumbers, "customerAccountNumbers cannot be null");
            customerAccountNumbers = List.copyOf(customerAccountNumbers);
        }
    }

    record Skipped(String reason) implements SeedReport {

        public Skipped {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }
}
