package com.bank.core.application.seed;

import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;

/**
 * Inversion-of-control adapter the F09 {@code SeedData} use case calls to open
 * customer accounts. Exists so the application module can drive F08's
 * transactional account-opening pipeline without violating F00's "application
 * is Spring-free" rule.
 *
 * <h2>Why a functional interface, not a direct dependency</h2>
 * The {@code @Transactional} boundary for an open-account call lives on
 * {@code com.bank.core.infrastructure.account.OpenAccountService}, but
 * application code cannot import infrastructure types and must not import
 * Spring annotations. The bootstrap layer satisfies this interface with the
 * method reference {@code openAccountService::open}, which preserves the
 * F08 transactional facade for every customer open and keeps this module
 * dependency-free.
 *
 * <p>Implementations MUST route every call through F08's
 * {@code @Transactional} facade — calling the plain-Java F08 use case
 * directly would defeat the per-customer atomicity F09 relies on.
 */
@FunctionalInterface
public interface OpensAccount {

    Account open(OpenAccountCommand command);
}
