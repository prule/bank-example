package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerificationStatusTest {

    @Test
    void pendingCanTransitionToVerifiedAndFailed() {
        assertTrue(VerificationStatus.PENDING.canTransitionTo(VerificationStatus.VERIFIED));
        assertTrue(VerificationStatus.PENDING.canTransitionTo(VerificationStatus.FAILED));
    }

    @Test
    void pendingCannotTransitionToPending() {
        assertFalse(VerificationStatus.PENDING.canTransitionTo(VerificationStatus.PENDING));
    }

    @Test
    void verifiedIsTerminal() {
        assertFalse(VerificationStatus.VERIFIED.canTransitionTo(VerificationStatus.PENDING));
        assertFalse(VerificationStatus.VERIFIED.canTransitionTo(VerificationStatus.FAILED));
        assertFalse(VerificationStatus.VERIFIED.canTransitionTo(VerificationStatus.VERIFIED));
    }

    @Test
    void failedIsTerminal() {
        assertFalse(VerificationStatus.FAILED.canTransitionTo(VerificationStatus.PENDING));
        assertFalse(VerificationStatus.FAILED.canTransitionTo(VerificationStatus.VERIFIED));
        assertFalse(VerificationStatus.FAILED.canTransitionTo(VerificationStatus.FAILED));
    }
}
