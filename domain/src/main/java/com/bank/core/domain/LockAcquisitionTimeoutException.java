package com.bank.core.domain;

public class LockAcquisitionTimeoutException extends DomainException {
    private final String firstAccount;
    private final String secondAccount;
    private final long waitMs;

    public LockAcquisitionTimeoutException(String firstAccount, String secondAccount, long waitMs) {
        super(String.format("Failed to acquire locks for accounts %s and %s within %d ms", firstAccount, secondAccount, waitMs));
        this.firstAccount = firstAccount;
        this.secondAccount = secondAccount;
        this.waitMs = waitMs;
    }

    public String firstAccount() {
        return firstAccount;
    }

    public String secondAccount() {
        return secondAccount;
    }

    public long waitMs() {
        return waitMs;
    }
}
