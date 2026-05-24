package com.bank.core.infrastructure.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dumb data-carrier bound from {@code bank.seed.*} in {@code application*.yaml}.
 * Holds the raw property values exactly as Spring's binder delivers them and
 * applies only null-safe defaults; all domain-level validation (positive money,
 * non-blank account numbers) happens later when {@code BankCoreApplication.seedPlan(...)}
 * lifts these values into {@link com.bank.core.application.seed.SeedPlan} and
 * its strongly-typed companions.
 *
 * <ul>
 *   <li>{@code enabled} — gate. Defaults to whatever Spring binds (absent → {@code false}).</li>
 *   <li>{@code clearingAccountNumber} — null falls back to {@code bank.clearing-account.number}
 *       at plan-construction time so the F08 and F09 clearing rows never drift apart.</li>
 *   <li>{@code clearingAccountOpeningBalance} — null defaults to {@code 100000.00},
 *       large enough to fund any sane dev plan.</li>
 *   <li>{@code customers} — null defaults to {@link List#of()}; empty list is legal
 *       (the runner then opens only the clearing account, which is rarely useful but
 *       not malformed).</li>
 * </ul>
 */
@ConfigurationProperties("bank.seed")
public record SeedProperties(
        boolean enabled,
        String clearingAccountNumber,
        BigDecimal clearingAccountOpeningBalance,
        List<CustomerSeedProperty> customers) {

    private static final BigDecimal DEFAULT_CLEARING_BALANCE = new BigDecimal("100000.00");

    public SeedProperties {
        if (clearingAccountOpeningBalance == null) {
            clearingAccountOpeningBalance = DEFAULT_CLEARING_BALANCE;
        }
        if (customers == null) {
            customers = List.of();
        }
    }

    /**
     * One customer entry. Validated by the domain types ({@code AccountNumber.of(...)},
     * {@code Money.of(...)}) when the plan is built, not here.
     */
    public record CustomerSeedProperty(String number, BigDecimal openingBalance) {
    }
}
