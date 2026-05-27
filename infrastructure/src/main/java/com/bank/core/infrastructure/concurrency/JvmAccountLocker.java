package com.bank.core.infrastructure.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnProperty(name = "bank.transfer.locker", havingValue = "jvm", matchIfMissing = true)
public class JvmAccountLocker implements AccountLocker {
    private static final Logger log = LoggerFactory.getLogger(JvmAccountLocker.class);

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final TransferLockingProperties properties;

    public JvmAccountLocker(TransferLockingProperties properties) {
        this.properties = properties;
        log.info("Initialized JvmAccountLocker with wait timeout of {} ms", properties.lockWaitMs());
    }

    @Override
    public long getWaitMs() {
        return properties.lockWaitMs();
    }

    @Override
    public void withPairedLocks(String a, String b, Runnable action) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Account numbers must not be null");
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("No active transaction found for lock acquisition");
        }

        String first = a.compareTo(b) <= 0 ? a : b;
        String second = a.compareTo(b) <= 0 ? b : a;

        ReentrantLock lock1 = locks.computeIfAbsent(first, k -> new ReentrantLock());

        if (first.equals(second)) {
            boolean acquired = false;
            try {
                acquired = lock1.tryLock(properties.lockWaitMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockAcquisitionTimeoutException(first, second, properties.lockWaitMs());
            }

            if (!acquired) {
                throw new LockAcquisitionTimeoutException(first, second, properties.lockWaitMs());
            }

            registerUnlockSynchronization(lock1);
        } else {
            ReentrantLock lock2 = locks.computeIfAbsent(second, k -> new ReentrantLock());

            boolean acquired1 = false;
            boolean acquired2 = false;

            try {
                acquired1 = lock1.tryLock(properties.lockWaitMs(), TimeUnit.MILLISECONDS);
                if (acquired1) {
                    acquired2 = lock2.tryLock(properties.lockWaitMs(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!acquired2) {
                    if (acquired1) {
                        lock1.unlock();
                    }
                    throw new LockAcquisitionTimeoutException(first, second, properties.lockWaitMs());
                }
            }

            registerUnlockSynchronization(lock1, lock2);
        }

        action.run();
    }

    private void registerUnlockSynchronization(ReentrantLock... locksToUnlock) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                for (ReentrantLock lock : locksToUnlock) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        });
    }

    public int getHoldCount(String accountNumber) {
        ReentrantLock lock = locks.get(accountNumber);
        return lock == null ? 0 : lock.getHoldCount();
    }
}
