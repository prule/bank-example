package com.bank.core.application.concurrency;

public interface AccountLocker {
    void withPairedLocks(String a, String b, Runnable action);
    long getWaitMs();
}
