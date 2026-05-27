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
import com.bank.core.application.account.OpenAccount;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.transfer.TransferFunds;
import org.springframework.beans.factory.annotation.Value;
import com.bank.core.application.ledger.VerifyPendingJournals;
import com.bank.core.application.ledger.AuditCheckpoints;
import com.bank.core.application.account.DetectBalanceDrift;

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

    @Bean
    public OpenAccount openAccount(Accounts accounts, TransferFunds transferFunds,
                                   @Value("${bank.clearing-account.number:CLEARING-000}") String clearingAccountNumber) {
        return new OpenAccount(accounts, transferFunds, clearingAccountNumber);
    }

    @Bean
    public VerifyPendingJournals verifyPendingJournals(JournalEntries journalEntries, Accounts accounts) {
        return new VerifyPendingJournals(journalEntries, accounts);
    }

    @Bean
    public DetectBalanceDrift detectBalanceDrift(AuditCheckpoints checkpoints, JournalEntries journalEntries, Accounts accounts) {
        return new DetectBalanceDrift(checkpoints, journalEntries, accounts);
    }
}
