package com.bank.core.infrastructure.seed;

import com.bank.core.application.seed.SeedData;
import com.bank.core.application.seed.SeedReport;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.ClearingAccountMissingException;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.InsufficientFundsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * F09 startup shell. Fires after the application context is fully refreshed
 * (so F08's {@code @Transactional} proxy on {@code OpenAccountService} is in
 * place) and delegates to {@link SeedData#seed()}, emitting one log line per
 * outcome and letting any failure abort startup.
 *
 * <h2>{@code ApplicationRunner}, not {@code @PostConstruct}</h2>
 * Per design.md Decision 1, {@link ApplicationRunner} is the only callback
 * Spring Boot guarantees runs after the full context — including
 * transactional proxies — is wired. {@code @PostConstruct} on this bean would
 * fire too early and might receive the raw, non-transactional F08 use case.
 *
 * <h2>{@code @ConditionalOnProperty}, not just a runtime check</h2>
 * Per design.md Decision 2, every seed bean is gated on
 * {@code bank.seed.enabled=true}. When seeding is off, this bean is never
 * constructed: no runner, no log line, no DB read, no actuator surface area.
 *
 * <h2>Per-customer atomicity is inherited</h2>
 * Per design.md Decision 3, the per-customer transactional boundary lives
 * on F08's {@code OpenAccountService}. This runner does not own a
 * {@code @Transactional} and does not wrap the customer loop in one. A
 * mid-seed failure rolls back the failing customer (F08 guarantee), logs
 * one ERROR line naming the failing account, and propagates the exception
 * so Spring Boot aborts startup with a non-zero exit.
 */
@Component
@ConditionalOnProperty(name = "bank.seed.enabled", havingValue = "true")
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SeedDataRunner.class);

    private final SeedData seedData;

    public SeedDataRunner(SeedData seedData) {
        this.seedData = Objects.requireNonNull(seedData, "seedData cannot be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedReport report;
        try {
            report = seedData.seed();
        } catch (RuntimeException ex) {
            LOG.error("dev seed failed: {} ({}: {})",
                    probableFailingNumber(ex),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            throw ex;
        }

        switch (report) {
            case SeedReport.Seeded seeded -> LOG.info(
                    "dev seed complete: clearing={} customers={} (count={})",
                    seeded.clearingAccountNumber().value(),
                    seeded.customerAccountNumbers().stream().map(AccountNumber::value).toList(),
                    seeded.customerAccountNumbers().size());
            case SeedReport.Skipped skipped -> LOG.info(
                    "dev seed skipped: {}", skipped.reason());
        }
    }

    private static String probableFailingNumber(RuntimeException ex) {
        if (ex instanceof DuplicateAccountNumberException dup) {
            return dup.number().value();
        }
        if (ex instanceof ClearingAccountMissingException missing) {
            return missing.clearingAccountNumber().value();
        }
        if (ex instanceof InsufficientFundsException) {
            return "<insufficient-funds>";
        }
        return "<unknown>";
    }
}
