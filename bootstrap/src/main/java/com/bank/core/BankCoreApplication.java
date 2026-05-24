package com.bank.core;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccount;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.AccountNumber;
import com.bank.core.infrastructure.concurrency.TransferLockingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication(scanBasePackages = "com.bank.core")
@EnableConfigurationProperties(TransferLockingProperties.class)
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
}
