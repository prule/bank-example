package com.bank.core.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for F10's background sweep. Bound from
 * {@code bank.journal-verification.*} in {@code application*.yaml}.
 *
 * <ul>
 *   <li>{@code fixedDelayMs} — gap between the end of one tick and the start
 *       of the next. Default {@code 10000} ms (spec target).</li>
 *   <li>{@code initialDelayMs} — delay before the first tick fires. Default
 *       {@code 5000} ms; tests override to {@code 0}.</li>
 *   <li>{@code pageSize} — max Pending journals processed per tick. Default
 *       {@code 50} (spec target).</li>
 * </ul>
 *
 * <p>Non-sensible values are coerced to the documented defaults via the
 * compact constructor and a single WARN log is emitted per fallback so
 * misconfiguration is loud at bean creation rather than mysteriously
 * underwhelming at runtime.
 */
@ConfigurationProperties("bank.journal-verification")
public record JournalVerificationProperties(long fixedDelayMs, long initialDelayMs, int pageSize) {

    private static final Logger LOG = LoggerFactory.getLogger(JournalVerificationProperties.class);

    private static final long DEFAULT_FIXED_DELAY_MS = 10_000L;
    private static final long DEFAULT_INITIAL_DELAY_MS = 5_000L;
    private static final int DEFAULT_PAGE_SIZE = 50;

    public JournalVerificationProperties {
        if (fixedDelayMs <= 0) {
            LOG.warn("bank.journal-verification.fixed-delay-ms must be positive (was: {}); falling back to {} ms",
                    fixedDelayMs, DEFAULT_FIXED_DELAY_MS);
            fixedDelayMs = DEFAULT_FIXED_DELAY_MS;
        }
        if (initialDelayMs < 0) {
            LOG.warn("bank.journal-verification.initial-delay-ms must be non-negative (was: {}); falling back to {} ms",
                    initialDelayMs, DEFAULT_INITIAL_DELAY_MS);
            initialDelayMs = DEFAULT_INITIAL_DELAY_MS;
        }
        if (pageSize <= 0) {
            LOG.warn("bank.journal-verification.page-size must be positive (was: {}); falling back to {}",
                    pageSize, DEFAULT_PAGE_SIZE);
            pageSize = DEFAULT_PAGE_SIZE;
        }
    }
}
