package com.bank.core.bootstrap;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import com.bank.core.infrastructure.concurrency.JvmAccountLocker;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class TransferLockingIntegrationTest {

    @Autowired
    private AccountLocker accountLocker;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    public void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        
        // Seed some account entities
        accountRepository.save(new AccountEntity("111111", AccountId.generate().toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity("222222", AccountId.generate().toString(), new BigDecimal("500.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testLockerBeanType() {
        assertThat(accountLocker).isInstanceOf(JvmAccountLocker.class);
    }

    @Test
    public void testSameAccountLocksOnce() {
        transactionTemplate.executeWithoutResult(status -> {
            JvmAccountLocker jvmLocker = (JvmAccountLocker) accountLocker;
            jvmLocker.withPairedLocks("111111", "111111", () -> {
                assertEquals(1, jvmLocker.getHoldCount("111111"));
            });
        });
    }

    @Test
    public void testCallOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> accountLocker.withPairedLocks("111111", "222222", () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active transaction found");
    }

    @Test
    public void testTransactionScopeReleaseOnCommit() throws Exception {
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch proceedCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> firstTxFuture = executor.submit(() -> {
            transactionTemplate.execute(status -> {
                accountLocker.withPairedLocks("111111", "222222", () -> {
                    transactionStarted.countDown();
                    try {
                        proceedCommit.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
        });

        try {
            transactionStarted.await();

            // Second transaction on this thread should block and timeout because locks are held by first transaction
            transactionTemplate.executeWithoutResult(status -> {
                assertThatThrownBy(() -> accountLocker.withPairedLocks("111111", "222222", () -> {}))
                        .isInstanceOf(LockAcquisitionTimeoutException.class);
            });
        } finally {
            // Let the first transaction commit and release everything
            proceedCommit.countDown();
            try {
                firstTxFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            executor.shutdownNow();
        }

        // Now the second transaction should succeed immediately without timeout
        transactionTemplate.executeWithoutResult(status -> {
            AtomicInteger runs = new AtomicInteger(0);
            accountLocker.withPairedLocks("111111", "222222", () -> runs.incrementAndGet());
            assertEquals(1, runs.get());
        });
    }

    @Test
    public void testTransactionScopeReleaseOnRollback() throws Exception {
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch proceedRollback = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> firstTxFuture = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    accountLocker.withPairedLocks("111111", "222222", () -> {
                        transactionStarted.countDown();
                        try {
                            proceedRollback.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    throw new RuntimeException("Force Rollback");
                });
            } catch (Exception ignored) {
            }
        });

        try {
            transactionStarted.await();

            // Second transaction on this thread should block and timeout because locks are held by first transaction
            transactionTemplate.executeWithoutResult(status -> {
                assertThatThrownBy(() -> accountLocker.withPairedLocks("111111", "222222", () -> {}))
                        .isInstanceOf(LockAcquisitionTimeoutException.class);
            });
        } finally {
            // Let the first transaction rollback
            proceedRollback.countDown();
            try {
                firstTxFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            executor.shutdownNow();
        }

        // Now the second transaction should succeed immediately without timeout
        transactionTemplate.executeWithoutResult(status -> {
            AtomicInteger runs = new AtomicInteger(0);
            accountLocker.withPairedLocks("111111", "222222", () -> runs.incrementAndGet());
            assertEquals(1, runs.get());
        });
    }

    @Test
    public void testUnrelatedAccountsProceedWithoutWaiting() throws Exception {
        CountDownLatch unrelatedTxStarted = new CountDownLatch(1);
        CountDownLatch holdLocks = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> {
            transactionTemplate.execute(status -> {
                accountLocker.withPairedLocks("111111", "222222", () -> {
                    unrelatedTxStarted.countDown();
                    try {
                        holdLocks.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
        });

        try {
            unrelatedTxStarted.await();

            // This transaction on unrelated accounts should proceed without blocking or timeout
            long start = System.currentTimeMillis();
            transactionTemplate.executeWithoutResult(status -> {
                accountLocker.withPairedLocks("333333", "444444", () -> {});
            });
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isLessThan(200);
        } finally {
            holdLocks.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testCanonicalOrderDoesNotDeadlock() throws Exception {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(threads);

        AtomicLong balanceA = new AtomicLong(1000);
        AtomicLong balanceB = new AtomicLong(1000);

        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final boolean direction = i % 2 == 0;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionTemplate.execute(status -> {
                        String a = direction ? "111111" : "222222";
                        String b = direction ? "222222" : "111111";
                        accountLocker.withPairedLocks(a, b, () -> {
                            if (direction) {
                                balanceA.decrementAndGet();
                                balanceB.incrementAndGet();
                            } else {
                                balanceB.decrementAndGet();
                                balanceA.incrementAndGet();
                            }
                        });
                        return null;
                    });
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    allFinished.countDown();
                }
            });
        }

        try {
            startLatch.countDown();
            assertTrue(allFinished.await(10, TimeUnit.SECONDS));

            assertEquals(0, failures.get());
            assertEquals(1000, balanceA.get());
            assertEquals(1000, balanceB.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testCrossPairContentionDoesNotDeadlock() throws Exception {
        CountDownLatch c1Started = new CountDownLatch(1);
        CountDownLatch proceedC2 = new CountDownLatch(1);
        CountDownLatch proceedC1 = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        AtomicInteger c1Runs = new AtomicInteger(0);
        AtomicInteger c2Runs = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            transactionTemplate.execute(status -> {
                accountLocker.withPairedLocks("111111", "222222", () -> {
                    c1Runs.incrementAndGet();
                    c1Started.countDown();
                    try {
                        proceedC1.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                c1Started.await();
                transactionTemplate.execute(status -> {
                    proceedC2.countDown();
                    accountLocker.withPairedLocks("333333", "111111", () -> {
                        c2Runs.incrementAndGet();
                    });
                    return null;
                });
            } catch (Exception ignored) {}
        });

        try {
            c1Started.await();
            proceedC2.await();

            // Thread 2 should be blocked waiting for A ("111111")
            Thread.sleep(100);
            assertEquals(0, c2Runs.get());
        } finally {
            // Let thread 1 finish
            proceedC1.countDown();
            try {
                f1.get(2, TimeUnit.SECONDS);
                f2.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            executor.shutdownNow();
        }

        assertEquals(1, c1Runs.get());
        assertEquals(1, c2Runs.get());
    }
}
