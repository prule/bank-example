package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.application.account.OpensAccount;
import com.bank.core.domain.Account;
import com.bank.core.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * SeedData coordinates the idempotent genesis seeding of the clearing account
 * and the sequential creation and funding of customer accounts.
 */
public class SeedData {
    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final Accounts accounts;
    private final OpensAccount opensAccount;

    public SeedData(Accounts accounts, OpensAccount opensAccount) {
        this.accounts = Objects.requireNonNull(accounts, "Accounts port must not be null");
        this.opensAccount = Objects.requireNonNull(opensAccount, "OpensAccount adapter must not be null");
    }

    /**
     * Idempotently executes the database seeding process based on the configured plan.
     *
     * @param plan the seeding plan configuration
     * @return a SeedReport detailing whether seeding completed or was skipped
     */
    public SeedReport seed(SeedPlan plan) {
        Objects.requireNonNull(plan, "SeedPlan must not be null");

        String clearingAccountNumber = plan.getClearingAccountNumber();

        // 1. Idempotency Check: Skip immediately if the clearing account is already present
        if (accounts.findByNumber(clearingAccountNumber).isPresent()) {
            return new SeedReport.Skipped("clearing account already present");
        }

        log.info("Starting database seeding: creating clearing account {}", clearingAccountNumber);

        // 2. Genesis Creation: Create and persist the clearing account directly at the configured balance
        Account clearingAccount = Account.open(clearingAccountNumber, Money.of(plan.getClearingAccountOpeningBalance()));
        try {
            accounts.save(clearingAccount);
        } catch (Exception ex) {
            log.error("Database seeding failed during clearing-account {} save: {}", clearingAccountNumber, ex.getClass().getName());
            throw ex;
        }

        // 3. Customer Creation: Fund and register customers sequentially via the transactional use case facade
        int seededCount = 0;
        for (SeedPlan.CustomerSeed customer : plan.getCustomers()) {
            log.info("Seeding customer account {} with opening balance {}", customer.number(), customer.openingBalance());
            OpenAccountCommand command = new OpenAccountCommand(customer.number(), Money.of(customer.openingBalance()));
            try {
                opensAccount.open(command);
                seededCount++;
            } catch (Exception ex) {
                log.error("Database seeding failed for customer {}: {}", customer.number(), ex.getClass().getName());
                throw ex;
            }
        }

        return new SeedReport.Seeded(seededCount);
    }
}
