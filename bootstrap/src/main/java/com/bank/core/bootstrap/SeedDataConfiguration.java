package com.bank.core.bootstrap;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpensAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class that registers development data seeding beans
 * strictly conditional on bank.seed.enabled resolving to true.
 */
@Configuration
@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")
public class SeedDataConfiguration {

    @Bean
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "bank.seed")
    public SeedPlan seedPlan(@Value("${bank.clearing-account.number:CLEARING-000}") String defaultClearingNumber) {
        SeedPlan plan = new SeedPlan();
        plan.setClearingAccountNumber(defaultClearingNumber);
        return plan;
    }

    @Bean
    public SeedData seedData(Accounts accounts, OpensAccount opensAccount) {
        return new SeedData(accounts, opensAccount);
    }

    @Bean
    public SeedDataRunner seedDataRunner(SeedPlan seedPlan, SeedData seedData) {
        return new SeedDataRunner(seedPlan, seedData);
    }
}
