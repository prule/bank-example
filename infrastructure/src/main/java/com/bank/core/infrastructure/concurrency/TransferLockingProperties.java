package com.bank.core.infrastructure.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link JvmAccountLocker}: the maximum time a paired-lock
 * acquisition will wait before failing fast with
 * {@link com.bank.core.domain.LockAcquisitionTimeoutException}.
 *
 * Bound from {@code bank.transfer.*} in {@code application.yaml} (default
 * 5000 ms) and overridden in the test profile to 500 ms so contention tests
 * fail fast rather than hanging the suite.
 */
@ConfigurationProperties(prefix = "bank.transfer")
public record TransferLockingProperties(long lockWaitMs) {
}
