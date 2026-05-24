package com.bank.core;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.infrastructure.concurrency.TransferLockingProperties;
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
}
