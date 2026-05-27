package com.bank.core.bootstrap;

/**
 * SeedReport represents the structured result of the database seeding run.
 */
public sealed interface SeedReport {
    record Seeded(int customerCount) implements SeedReport {}
    record Skipped(String reason) implements SeedReport {}
}
