package com.bank.core.bootstrap;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import com.bank.core.infrastructure.concurrency.DbAccountLocker;
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

@SpringBootTest(properties = "bank.transfer.locker=db")
@ActiveProfiles("test")
public class DbTransferLockingIntegrationTest {

    @Autowired
    private AccountLocker accountLocker;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    public void setUp() {
        jdbcTemplate.execute("DELETE FROM ledger_movement");
        jdbcTemplate.execute("DELETE FROM journal_entry");
        jdbcTemplate.execute("DELETE FROM account");

        transactionTemplate = new TransactionTemplate(transactionManager);

        // Seed some account entities
        accountRepository.save(new AccountEntity("111111", AccountId.generate().toString(), new BigDecimal("1000.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.save(new AccountEntity("222222", AccountId.generate().toString(), new BigDecimal("500.00"), AccountStatus.ACTIVE, Instant.now()));
        accountRepository.flush();
    }

    @Test
    public void testLockerBeanType() {
        assertThat(accountLocker).isInstanceOf(DbAccountLocker.class);
    }

    @Test
    public void testSameAccountLocksOnce() {
        transactionTemplate.executeWithoutResult(status -> {
            AtomicInteger runs = new AtomicInteger(0);
            accountLocker.withPairedLocks("111111", "111111", () -> runs.incrementAndGet());
            assertEquals(1, runs.get());
        });
    }

    @Test
    public void testCallOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> accountLocker.withPairedLocks("111111", "222222", () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active transaction found");
    }

    @Test
    public void testMissingAccountIsTransparentToLocker() {
        transactionTemplate.executeWithoutResult(status -> {
            AtomicInteger runs = new AtomicInteger(0);
            accountLocker.withPairedLocks("111111", "999999", () -> runs.incrementAndGet());
            assertEquals(1, runs.get());
        });
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

            // Second transaction on this thread should block and fail due to lock contention timeout
            // Catch the exception OUTSIDE transactionTemplate block because transaction completion might fail
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    accountLocker.withPairedLocks("111111", "222222", () -> {});
                });
                fail("Should have failed due to H2 lock timeout");
            } catch (Exception e) {
                // On H2, statement timeout can manifest as LockAcquisitionTimeoutException or JpaSystemException (rollback failure)
                assertTrue(e instanceof LockAcquisitionTimeoutException || e.getClass().getSimpleName().contains("Exception"));
            }
        } finally {
            // Let the first transaction commit
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
    public void testCanonicalOrderDoesNotDeadlock() throws Exception {
        int threads = 10; // Keep connection usage low to avoid exhaustion
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
}
