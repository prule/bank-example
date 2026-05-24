package com.bank.core.application.concurrency;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.LockAcquisitionTimeoutException;

/**
 * Port for acquiring paired exclusive write locks on two accounts in a single
 * canonical order, executing a unit of work while holding both locks, and
 * releasing them at the surrounding transaction's commit or rollback.
 *
 * The canonical order is "lower {@link AccountNumber#value()} first by
 * {@link String#compareTo}". The port enforces this regardless of caller
 * argument order — a call with {@code (A, B)} and a concurrent call with
 * {@code (B, A)} both acquire the same lock first, so two opposite-direction
 * transfers between the same pair of accounts never deadlock.
 *
 * Plain Java by design: no Spring annotations, no JPA types, no
 * openapi-generated imports. F00's ArchUnit boundary rule pins the application
 * module to this shape. The single implementation lives in the infrastructure
 * module under {@code com.bank.core.infrastructure.concurrency}; build-time
 * ArchUnit rules confine the underlying JVM-lock and transaction-hook types
 * to that package, so this is the only paired-lock acquirer in the codebase.
 * See {@code com.bank.core.infrastructure.concurrency.JvmAccountLocker} for
 * the JVM-lock adapter (transaction-synchronisation hooks handle release).
 *
 * Downstream consumers:
 * <ul>
 *   <li>F06 (fund transfer) will inject this port and structure its use case
 *       as {@code locker.withPairedLocks(source, destination, () -> { debit;
 *       credit; save journal; })}. F06 will also add the
 *       {@code ExceptionHandler(LockAcquisitionTimeoutException)} entry
 *       to F03's {@code GlobalExceptionHandler}.</li>
 *   <li>F08 (account opening) seeds new accounts via a clearing-account
 *       transfer through F06, so it inherits this port transitively.</li>
 * </ul>
 */
public interface AccountLocker {

    /**
     * Acquire exclusive write locks on both accounts in canonical order
     * (lower {@link AccountNumber#value()} first), run {@code inTransaction},
     * then arrange for both locks to be released at the surrounding
     * transaction's commit or rollback.
     *
     * Calls with {@code a.equals(b)} acquire the lock once and proceed.
     *
     * @throws IllegalStateException             if no transaction is active at the call site
     * @throws LockAcquisitionTimeoutException   if either lock cannot be
     *                                           acquired within the configured wait budget; any
     *                                           lock acquired on the way to the failure is
     *                                           released before this is thrown
     * @throws NullPointerException              if any argument is null
     */
    void withPairedLocks(AccountNumber a, AccountNumber b, Runnable inTransaction);
}
