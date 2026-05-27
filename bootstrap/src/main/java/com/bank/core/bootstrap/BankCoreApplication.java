package com.bank.core.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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
}
