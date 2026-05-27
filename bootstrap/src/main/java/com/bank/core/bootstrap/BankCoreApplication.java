package com.bank.core.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
import com.bank.core.application.account.Accounts;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.transfer.TransferFunds;

@SpringBootApplication(scanBasePackages = "com.bank.core")
@EnableJpaRepositories(basePackages = "com.bank.core")
@EntityScan(basePackages = "com.bank.core")
@ConfigurationPropertiesScan(basePackages = "com.bank.core")
@EnableScheduling
@EnableAsync
public class BankCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankCoreApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TransferFunds transferFunds(Accounts accounts, JournalEntries journalEntries, AccountLocker locker, Clock clock) {
        return new TransferFunds(accounts, journalEntries, locker, clock);
    }
}
