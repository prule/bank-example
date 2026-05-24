package com.bank.core.domain;

public enum VerificationStatus {
    PENDING,
    VERIFIED,
    FAILED;

    public boolean canTransitionTo(VerificationStatus next) {
        if (this != PENDING) {
            return false;
        }
        return next == VERIFIED || next == FAILED;
    }
}
