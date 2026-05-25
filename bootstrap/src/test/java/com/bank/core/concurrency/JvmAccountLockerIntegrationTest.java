package com.bank.core.concurrency;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import com.bank.core.infrastructure.concurrency.JvmAccountLocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the F07 transfer-locking contract. Boots the real
 * {@link JvmAccountLocker} inside Spring and exercises the spec's scenarios
 * via the public {@link AccountLocker} port through a {@link TransactionTemplate}
 * (so {@link org.springframework.transaction.support.TransactionSynchronization}
 * release callbacks fire for real on commit/rollback).
 *
 * Wait budget comes from {@code application-test.yaml} (500 ms), so contention
 * tests fail fast rather than hanging the suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountLockerIntegrationTest {

    @Autowired
    AccountLocker locker;

    @Autowired
    JvmAccountLocker jvmLocker;

    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private static AccountNumber acct(String value) {
        return AccountNumber.of(value);
    }

    @Test
    void waitBudgetMatchesTestProfile() {
        assertThat(jvmLocker.waitMs()).isEqualTo(500L);
    }

    /**
     * 6.2 — Order independence: T1 holds canonical-first lock for the pair
     * via a (A, A) self-lock; both (A, B) and (B, A) calls block on it.
     * Proves both argument orderings canonicalize on the same first lock.
     */
    @Test
    void lockOrderIsIndependentOfCallerArgumentOrder() throws Exception {
        AccountNumber a = acct("ACC-001");
        AccountNumber b = acct("ACC-002");
        AccountNumber canonicalFirst = a; // "ACC-001" < "ACC-002"

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // Holder thread: takes the canonical-first lock and waits.
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(canonicalFirst, canonicalFirst, () -> {
                    holderInside.countDown();
                    try {
                        holderRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            }));

            assertThat(holderInside.await(2, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> abError = new AtomicReference<>();
            AtomicReference<Throwable> baError = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(2);

            // Forward direction (A, B) — should block on canonical-first lock,
            // then time out because holder still holds it.
            pool.submit(() -> {
                try {
                    tx().execute(status -> {
                        locker.withPairedLocks(a, b, () -> { });
                        return null;
                    });
                } catch (Throwable t) {
                    abError.set(t);
                } finally {
                    finished.countDown();
                }
            });

            // Reverse direction (B, A) — same expected outcome.
            pool.submit(() -> {
                try {
                    tx().execute(status -> {
                        locker.withPairedLocks(b, a, () -> { });
                        return null;
                    });
                } catch (Throwable t) {
                    baError.set(t);
                } finally {
                    finished.countDown();
                }
            });

            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();

            // Both directions timed out on the same canonical-first lock.
            assertThat(abError.get())
                    .as("(A, B) should time out on canonical-first lock")
                    .isInstanceOf(LockAcquisitionTimeoutException.class);
            assertThat(baError.get())
                    .as("(B, A) should time out on canonical-first lock")
                    .isInstanceOf(LockAcquisitionTimeoutException.class);

            LockAcquisitionTimeoutException abEx = (LockAcquisitionTimeoutException) abError.get();
            LockAcquisitionTimeoutException baEx = (LockAcquisitionTimeoutException) baError.get();
            // Both exceptions reference the canonical-first account.
            assertThat(abEx.firstAccount()).isEqualTo(canonicalFirst);
            assertThat(baEx.firstAccount()).isEqualTo(canonicalFirst);

            holderRelease.countDown();
        } finally {
            holderRelease.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 6.3 — Counter-direction stress: 100 threads, half (A→B), half (B→A).
     * Net balance must be zero, no deadlocks, no timeouts.
     */
    @Test
    void counterDirectionStressDoesNotDeadlockOrCorruptBalances() throws Exception {
        AccountNumber a = acct("STRESS-A");
        AccountNumber b = acct("STRESS-B");

        Map<AccountNumber, AtomicLong> balances = new HashMap<>();
        balances.put(a, new AtomicLong(0));
        balances.put(b, new AtomicLong(0));

        int transfers = 100;
        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(transfers);
        AtomicInteger timeouts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 0; i < transfers; i++) {
                AccountNumber src = (i % 2 == 0) ? a : b;
                AccountNumber dst = (i % 2 == 0) ? b : a;
                pool.submit(() -> {
                    try {
                        start.await();
                        tx().execute(status -> {
                            locker.withPairedLocks(src, dst, () -> {
                                balances.get(src).addAndGet(-1);
                                balances.get(dst).addAndGet(+1);
                            });
                            return null;
                        });
                    } catch (LockAcquisitionTimeoutException ex) {
                        timeouts.incrementAndGet();
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            long t0 = System.nanoTime();
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS))
                    .as("100 counter-direction transfers should complete within 10s")
                    .isTrue();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(elapsedMs).isLessThan(10_000);
            assertThat(timeouts.get()).as("no LockAcquisitionTimeoutException").isZero();
            assertThat(failures.get()).as("no unexpected failures").isZero();
            assertThat(balances.get(a).get()).isZero();
            assertThat(balances.get(b).get()).isZero();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 6.4 — Cross-pair non-blocking: T1 holds (A, B); T2 acquires (C, D) promptly.
     */
    @Test
    void unrelatedPairsDoNotBlockEachOther() throws Exception {
        AccountNumber a = acct("CP-A");
        AccountNumber b = acct("CP-B");
        AccountNumber c = acct("CP-C");
        AccountNumber d = acct("CP-D");

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(a, b, () -> {
                    holderInside.countDown();
                    try {
                        holderRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            }));

            assertThat(holderInside.await(2, TimeUnit.SECONDS)).isTrue();

            long t0 = System.nanoTime();
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(c, d, () -> { });
                return null;
            })).get(2, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(elapsedMs)
                    .as("unrelated pair (C, D) should not wait on (A, B)")
                    .isLessThan(200L);

            holderRelease.countDown();
        } finally {
            holderRelease.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 6.5 — Released on commit: subsequent acquisition of the same pair
     * succeeds immediately after the first transaction commits.
     */
    @Test
    void locksAreReleasedOnCommit() {
        AccountNumber a = acct("COM-A");
        AccountNumber b = acct("COM-B");

        tx().execute(status -> {
            locker.withPairedLocks(a, b, () -> { });
            return null;
        });

        long t0 = System.nanoTime();
        tx().execute(status -> {
            locker.withPairedLocks(a, b, () -> { });
            return null;
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs)
                .as("second acquisition after commit should not wait")
                .isLessThan(50L);
    }

    /**
     * 6.6 — Released on rollback: when the runnable throws, the surrounding
     * transaction rolls back and the locks still release.
     */
    @Test
    void locksAreReleasedOnRollback() {
        AccountNumber a = acct("RB-A");
        AccountNumber b = acct("RB-B");

        assertThatThrownBy(() -> tx().execute(status -> {
            locker.withPairedLocks(a, b, () -> {
                throw new IllegalStateException("boom inside runnable");
            });
            return null;
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        long t0 = System.nanoTime();
        tx().execute(status -> {
            locker.withPairedLocks(a, b, () -> { });
            return null;
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs)
                .as("second acquisition after rollback should not wait")
                .isLessThan(50L);
    }

    /**
     * 6.7 — Timeout surfaces as LockAcquisitionTimeoutException carrying both
     * account numbers in canonical order and the configured waitMs.
     */
    @Test
    void timeoutSurfacesAsLockAcquisitionTimeoutException() throws Exception {
        AccountNumber a = acct("TO-A");
        AccountNumber b = acct("TO-B");
        AccountNumber canonicalFirst = a;
        AccountNumber canonicalSecond = b;

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(canonicalFirst, canonicalFirst, () -> {
                    holderInside.countDown();
                    try {
                        holderRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            }));

            assertThat(holderInside.await(2, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> error = new AtomicReference<>();
            pool.submit(() -> {
                try {
                    tx().execute(status -> {
                        // Call with reversed args to also exercise canonical reordering.
                        locker.withPairedLocks(canonicalSecond, canonicalFirst, () -> { });
                        return null;
                    });
                } catch (Throwable t) {
                    error.set(t);
                }
            }).get(3, TimeUnit.SECONDS);

            assertThat(error.get()).isInstanceOf(LockAcquisitionTimeoutException.class);
            LockAcquisitionTimeoutException ex = (LockAcquisitionTimeoutException) error.get();
            assertThat(ex.firstAccount()).isEqualTo(canonicalFirst);
            assertThat(ex.secondAccount()).isEqualTo(canonicalSecond);
            assertThat(ex.waitMs()).isEqualTo(500L);

            holderRelease.countDown();
        } finally {
            holderRelease.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 6.8 — Call made outside an active transaction is rejected before any
     * lock is acquired and the runnable does not execute.
     */
    @Test
    void callOutsideTransactionIsRejected() {
        AccountNumber a = acct("NT-A");
        AccountNumber b = acct("NT-B");
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() ->
                locker.withPairedLocks(a, b, runs::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        assertThat(runs.get()).isZero();
    }

    /**
     * 6.9 — Same-account call: runnable executes; only one lock is acquired
     * (hold count is 1 inside the callback, verified via re-entry).
     */
    @Test
    void sameAccountCallLocksOnce() {
        AccountNumber a = acct("SAME-A");
        AtomicInteger runs = new AtomicInteger();

        tx().execute(status -> {
            locker.withPairedLocks(a, a, () -> {
                runs.incrementAndGet();
                // Re-entrant call from the same thread also inside the same
                // transaction succeeds without waiting (ReentrantLock allows
                // re-entry), and observes the same single underlying lock.
                locker.withPairedLocks(a, a, runs::incrementAndGet);
            });
            return null;
        });

        assertThat(runs.get()).isEqualTo(2);

        // After commit, the lock is released and the next caller proceeds.
        long t0 = System.nanoTime();
        tx().execute(status -> {
            locker.withPairedLocks(a, a, () -> { });
            return null;
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).isLessThan(50L);
    }

    /**
     * Spec scenario "Exception carries diagnostic context": timeout while
     * waiting on the SECOND of a pair returns an exception carrying both
     * account numbers in canonical order; the first lock is released
     * before the exception propagates.
     */
    @Test
    void timeoutOnSecondLockCarriesDiagnosticContextAndReleasesFirst() throws Exception {
        AccountNumber a = acct("DX-A");
        AccountNumber b = acct("DX-B");
        AccountNumber canonicalFirst = a;
        AccountNumber canonicalSecond = b;

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // Holder takes canonicalSecond's lock; requester acquires canonicalFirst
            // unopposed, then blocks on canonicalSecond.
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(canonicalSecond, canonicalSecond, () -> {
                    holderInside.countDown();
                    try {
                        holderRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            }));

            assertThat(holderInside.await(2, TimeUnit.SECONDS)).isTrue();

            List<LockAcquisitionTimeoutException> caught = new java.util.ArrayList<>();
            pool.submit(() -> {
                try {
                    tx().execute(status -> {
                        locker.withPairedLocks(canonicalFirst, canonicalSecond, () -> { });
                        return null;
                    });
                } catch (LockAcquisitionTimeoutException ex) {
                    caught.add(ex);
                }
            }).get(3, TimeUnit.SECONDS);

            assertThat(caught).hasSize(1);
            LockAcquisitionTimeoutException ex = caught.get(0);
            assertThat(ex.firstAccount().value()).isEqualTo("DX-A");
            assertThat(ex.secondAccount().value()).isEqualTo("DX-B");
            assertThat(ex.waitMs()).isEqualTo(500L);
            assertThat(ex.getMessage()).contains("DX-A").contains("DX-B").contains("500");

            // First lock must have been released before the exception propagated:
            // another thread can acquire canonicalFirst immediately even while
            // the holder still owns canonicalSecond.
            long t0 = System.nanoTime();
            pool.submit(() -> tx().execute(status -> {
                locker.withPairedLocks(canonicalFirst, canonicalFirst, () -> { });
                return null;
            })).get(2, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(elapsedMs)
                    .as("first lock should have been released before exception")
                    .isLessThan(100L);

            holderRelease.countDown();
        } finally {
            holderRelease.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
