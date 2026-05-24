package com.bank.core;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccount;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.seed.ClearingAccountSeed;
import com.bank.core.application.seed.CustomerSeed;
import com.bank.core.application.seed.SeedData;
import com.bank.core.application.seed.SeedPlan;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import com.bank.core.infrastructure.account.OpenAccountService;
import com.bank.core.infrastructure.concurrency.TransferLockingProperties;
import com.bank.core.infrastructure.seed.SeedProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.List;

@SpringBootApplication(scanBasePackages = "com.bank.core")
@EnableConfigurationProperties({TransferLockingProperties.class, SeedProperties.class})
@EnableScheduling
@EnableAsync
public class BankCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankCoreApplication.class, args);
    }

    /**
     * Production wall clock injected into the F06 fund-transfer use case so
     * journal-entry timestamps are deterministic in tests via Clock.fixed(...).
     */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * F06 fund-transfer use case. Plain-Java per F02's
     * {@code transactional-in-application} precedent; the @Transactional
     * boundary lives on TransferController.createTransfer.
     */
    @Bean
    TransferFunds transferFunds(Accounts accounts,
                                JournalEntries journals,
                                AccountLocker locker,
                                Clock clock) {
        return new TransferFunds(accounts, journals, locker, clock);
    }

    /**
     * F08 account-opening use case. Plain Java per F02's
     * {@code transactional-in-application} precedent; the @Transactional
     * boundary lives on {@code OpenAccountService}. The clearing-account
     * number is read from {@code bank.clearing-account.number} (default
     * {@code CLEARING-000}) and wrapped in {@link AccountNumber} here so the
     * application module stays free of {@code @Value} and Spring imports.
     * A blank value fails at bean construction via {@link AccountNumber}'s
     * non-blank invariant.
     */
    @Bean
    OpenAccount openAccount(Accounts accounts,
                            TransferFunds transferFunds,
                            @Value("${bank.clearing-account.number}") String clearingAccountNumber) {
        return new OpenAccount(accounts, transferFunds, AccountNumber.of(clearingAccountNumber));
    }

    /**
     * F09 seed plan. Lifts {@link SeedProperties} (a dumb DTO) into the
     * strongly-typed {@link SeedPlan}, applying the domain invariants:
     * {@link AccountNumber} rejects blanks, {@link Money} rejects negatives,
     * and {@link ClearingAccountSeed} rejects a zero clearing balance. The
     * clearing-account number falls back to the F08 property
     * {@code bank.clearing-account.number} when {@code bank.seed.clearingAccountNumber}
     * is null or blank so F08 and F09 always target the same row (design.md
     * Decision 7). Gated on {@code bank.seed.enabled=true} so the plan
     * bean is not constructed when seeding is off (design.md Decision 2).
     */
    @Bean
    @ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")
    SeedPlan seedPlan(SeedProperties props,
                      @Value("${bank.clearing-account.number}") String fallbackClearingNumber) {
        String resolved = (props.clearingAccountNumber() != null && !props.clearingAccountNumber().isBlank())
                ? props.clearingAccountNumber()
                : fallbackClearingNumber;
        ClearingAccountSeed clearing = new ClearingAccountSeed(
                AccountNumber.of(resolved),
                Money.of(props.clearingAccountOpeningBalance()));
        List<CustomerSeed> customers = props.customers().stream()
                .map(c -> new CustomerSeed(AccountNumber.of(c.number()), Money.of(c.openingBalance())))
                .toList();
        return new SeedPlan(clearing, customers);
    }

    /**
     * F09 seed use case. Wraps F08's {@code OpenAccountService::open} method
     * reference as the {@code OpensAccount} adapter — this keeps the
     * application module free of Spring annotations and infrastructure
     * imports (design.md Decision 5) while routing every customer open
     * through F08's {@code @Transactional} facade so per-customer atomicity
     * is preserved. Gated on {@code bank.seed.enabled=true}.
     */
    @Bean
    @ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")
    SeedData seedData(Accounts accounts, OpenAccountService openAccountService, SeedPlan plan) {
        return new SeedData(accounts, openAccountService::open, plan);
    }
}
