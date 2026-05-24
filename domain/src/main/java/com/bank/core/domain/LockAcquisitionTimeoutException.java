package com.bank.core.domain;

import java.util.Objects;

/**
 * Thrown when a transfer cannot acquire the paired write locks on its two
 * accounts within the configured wait budget ({@code bank.transfer.lock-wait-ms}).
 *
 * The exception is always thrown with no lock held: any lock acquired on the
 * way to the failure has been released before this propagates.
 *
 * Lives in the domain module so F03's global exception handler can map it at
 * the HTTP boundary without the web layer importing anything from the
 * concurrency adapter.
 */
public final class LockAcquisitionTimeoutException extends DomainException {

    private final AccountNumber firstAccount;
    private final AccountNumber secondAccount;
    private final long waitMs;

    public LockAcquisitionTimeoutException(AccountNumber firstAccount, AccountNumber secondAccount, long waitMs) {
        this(firstAccount, secondAccount, waitMs, null);
    }

    public LockAcquisitionTimeoutException(AccountNumber firstAccount, AccountNumber secondAccount, long waitMs, Throwable cause) {
        super("Timed out after " + waitMs + "ms acquiring paired locks on accounts "
                + Objects.requireNonNull(firstAccount, "firstAccount cannot be null")
                + " and " + Objects.requireNonNull(secondAccount, "secondAccount cannot be null"));
        if (cause != null) {
            initCause(cause);
        }
        this.firstAccount = firstAccount;
        this.secondAccount = secondAccount;
        this.waitMs = waitMs;
    }

    public AccountNumber firstAccount() {
        return firstAccount;
    }

    public AccountNumber secondAccount() {
        return secondAccount;
    }

    public long waitMs() {
        return waitMs;
    }
}
