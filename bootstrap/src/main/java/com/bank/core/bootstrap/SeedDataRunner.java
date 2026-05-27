package com.bank.core.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;

/**
 * SeedDataRunner executes the dev database seeding on application startup if conditional checks pass.
 */
public class SeedDataRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final SeedPlan plan;
    private final SeedData seedData;

    public SeedDataRunner(SeedPlan plan, SeedData seedData) {
        this.plan = Objects.requireNonNull(plan, "SeedPlan must not be null");
        this.seedData = Objects.requireNonNull(seedData, "SeedData must not be null");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        SeedReport report = seedData.seed(plan);
        if (report instanceof SeedReport.Seeded seeded) {
            log.info("dev seed complete: {} customer accounts seeded successfully", seeded.customerCount());
        } else if (report instanceof SeedReport.Skipped skipped) {
            log.info("dev seed skipped: {}", skipped.reason());
        }
    }
}
