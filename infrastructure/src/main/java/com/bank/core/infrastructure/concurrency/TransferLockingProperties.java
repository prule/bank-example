package com.bank.core.infrastructure.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the transfer locker.
 *
 * <ul>
 *   <li>{@code lockWaitMs} — maximum time a paired-lock acquisition will wait
 *       before failing fast with
 *       {@link com.bank.core.domain.LockAcquisitionTimeoutException}. Bound
 *       from {@code bank.transfer.lock-wait-ms} (default 5000 ms;
 *       test profile overrides to 500 ms so contention tests fail fast).</li>
 *   <li>{@code strategy} — selects the locker implementation:
 *       {@code "jvm"} (default, single-instance, JVM
 *       {@link java.util.concurrent.locks.ReentrantLock}) or
 *       {@code "db"} (multi-instance, {@code SELECT ... FOR UPDATE} against
 *       the {@code account} table). Bound from {@code bank.transfer.locker};
 *       case-insensitive. Any other value fails the application context
 *       build at construction time with an {@link IllegalArgumentException}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "bank.transfer")
public record TransferLockingProperties(long lockWaitMs, String strategy) {

    public TransferLockingProperties {
        String originalStrategy = strategy;
        if (strategy == null || strategy.isBlank()) {
            strategy = "jvm";
        } else {
            strategy = strategy.toLowerCase();
        }
        if (!strategy.equals("jvm") && !strategy.equals("db")) {
            throw new IllegalArgumentException(
                    "bank.transfer.locker must be 'jvm' or 'db' (was: '" + originalStrategy + "')");
        }
    }
}
