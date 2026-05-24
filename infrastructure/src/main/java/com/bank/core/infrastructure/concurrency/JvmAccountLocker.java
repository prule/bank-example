package com.bank.core.infrastructure.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM-level implementation of {@link AccountLocker} backed by a process-wide
 * {@link ConcurrentHashMap} of {@link ReentrantLock}s keyed by
 * {@link AccountNumber}. Acquires both locks in canonical order
 * ({@code lower AccountNumber.value() first}), then registers a
 * {@link TransactionSynchronization#afterCompletion} callback that releases
 * them on commit or rollback.
 *
 * <h2>Single-instance assumption</h2>
 * This adapter assumes one application instance. A second JVM running against
 * the same database would defeat the lock — the canonical-order rule would
 * hold within each JVM but not globally. The {@code multi-instance} open
 * decision in {@code openspec/config.yaml} tracks the path to a DB-row-lock
 * adapter when the deployment topology demands it; the {@link AccountLocker}
 * port abstracts the choice so the swap is local to this package.
 *
 * <h2>Why this is the only paired-lock acquirer</h2>
 * Two ArchUnit rules in {@code ModuleBoundaryTest} forbid any class outside
 * {@code com.bank.core.infrastructure.concurrency..} from depending on
 * {@link ReentrantLock} or {@link TransactionSynchronizationManager} in
 * production sources. The compile-time guard plus the runtime
 * {@code IllegalStateException} on missing transactions are the two halves
 * of the "single source of truth" requirement (transfer-locking spec,
 * "Single lock-acquisition component").
 */
@Component
public final class JvmAccountLocker implements AccountLocker {

    private static final Logger log = LoggerFactory.getLogger(JvmAccountLocker.class);

    private final ConcurrentHashMap<AccountNumber, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final long waitMs;

    public JvmAccountLocker(TransferLockingProperties properties) {
        this.waitMs = properties.lockWaitMs();
    }

    public long waitMs() {
        return waitMs;
    }

    @Override
    public void withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction) {
        Objects.requireNonNull(a, "first account cannot be null");
        Objects.requireNonNull(b, "second account cannot be null");
        Objects.requireNonNull(inTransaction, "runnable cannot be null");

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "paired locks require an active transaction (no synchronization registered for thread "
                            + Thread.currentThread().getName() + ")");
        }

        AccountNumber first;
        AccountNumber second;
        if (a.equals(b)) {
            first = a;
            second = a;
        } else if (a.value().compareTo(b.value()) <= 0) {
            first = a;
            second = b;
        } else {
            first = b;
            second = a;
        }

        boolean sameAccount = first.equals(second);

        ReentrantLock firstLock = locks.computeIfAbsent(first, k -> new ReentrantLock());
        acquire(firstLock, first, second);

        ReentrantLock secondLock;
        if (sameAccount) {
            secondLock = null;
        } else {
            secondLock = locks.computeIfAbsent(second, k -> new ReentrantLock());
            try {
                acquire(secondLock, first, second);
            } catch (RuntimeException | Error ex) {
                firstLock.unlock();
                throw ex;
            }
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (secondLock != null) {
                    secondLock.unlock();
                }
                firstLock.unlock();
                log.debug("released paired locks on {}, {} (txStatus={})", first, second, status);
            }
        });

        log.debug("acquired paired locks on {}, {} (waitMs={})", first, second, waitMs);
        inTransaction.run();
    }

    private void acquire(ReentrantLock lock, AccountNumber first, AccountNumber second) {
        long start = System.nanoTime();
        boolean granted;
        try {
            granted = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("interrupted while waiting for lock on {}/{} after {}ms", first, second, elapsedMs);
            throw new LockAcquisitionTimeoutException(first, second, 0, ie);
        }
        if (!granted) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("timed out acquiring lock on {}/{} after {}ms (budget {}ms)", first, second, elapsedMs, waitMs);
            throw new LockAcquisitionTimeoutException(first, second, waitMs);
        }
    }
}
