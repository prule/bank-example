package com.bank.core;

import com.bank.core.infrastructure.concurrency.TransferLockingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.bank.core")
@EnableConfigurationProperties(TransferLockingProperties.class)
@EnableScheduling
@EnableAsync
public class BankCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankCoreApplication.class, args);
    }
}
