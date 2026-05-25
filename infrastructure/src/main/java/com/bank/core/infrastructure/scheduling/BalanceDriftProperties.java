package com.bank.core.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for F11's {@code BalanceDriftScheduler}: the fixed delay
 * between audit ticks and the initial delay before the first tick.
 *
 * <p>Defaults match the {@code balance-drift-detection} spec target of
 * "every 30 seconds" and stagger the first run 15 s out so the audit does
 * not race the application context's first POST handler. The 15 s offset
 * also avoids colliding with F10's {@code journal-verification} 5 s initial
 * delay so the two scheduled tasks do not fire at the same second after boot.
 *
 * <p>The compact constructor falls back to the documented defaults when
 * Spring's binder supplies a non-positive value (typically a misconfiguration
 * in {@code application.yaml}); a single WARN line names the offending field
 * so operators can catch the typo.
 */
@ConfigurationProperties(prefix = "bank.balance-drift")
public record BalanceDriftProperties(long fixedDelayMs, long initialDelayMs) {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceDriftProperties.class);

    private static final long DEFAULT_FIXED_DELAY_MS = 30_000L;
    private static final long DEFAULT_INITIAL_DELAY_MS = 15_000L;

    public BalanceDriftProperties {
        if (fixedDelayMs <= 0) {
            LOG.warn("bank.balance-drift.fixed-delay-ms must be positive (was {}); falling back to {}",
                    fixedDelayMs, DEFAULT_FIXED_DELAY_MS);
            fixedDelayMs = DEFAULT_FIXED_DELAY_MS;
        }
        if (initialDelayMs < 0) {
            LOG.warn("bank.balance-drift.initial-delay-ms must be non-negative (was {}); falling back to {}",
                    initialDelayMs, DEFAULT_INITIAL_DELAY_MS);
            initialDelayMs = DEFAULT_INITIAL_DELAY_MS;
        }
    }
}
