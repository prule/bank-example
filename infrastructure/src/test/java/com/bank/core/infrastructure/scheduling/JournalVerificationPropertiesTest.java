package com.bank.core.infrastructure.scheduling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JournalVerificationPropertiesTest {

    @Test
    void positiveValuesRoundTrip() {
        JournalVerificationProperties props = new JournalVerificationProperties(2000L, 100L, 25);
        assertEquals(2000L, props.fixedDelayMs());
        assertEquals(100L, props.initialDelayMs());
        assertEquals(25, props.pageSize());
    }

    @Test
    void zeroFixedDelay_fallsBackToDefault() {
        JournalVerificationProperties props = new JournalVerificationProperties(0L, 0L, 50);
        assertEquals(10_000L, props.fixedDelayMs());
    }

    @Test
    void negativeFixedDelay_fallsBackToDefault() {
        JournalVerificationProperties props = new JournalVerificationProperties(-1L, 0L, 50);
        assertEquals(10_000L, props.fixedDelayMs());
    }

    @Test
    void zeroPageSize_fallsBackToDefault() {
        JournalVerificationProperties props = new JournalVerificationProperties(10_000L, 5_000L, 0);
        assertEquals(50, props.pageSize());
    }

    @Test
    void negativePageSize_fallsBackToDefault() {
        JournalVerificationProperties props = new JournalVerificationProperties(10_000L, 5_000L, -10);
        assertEquals(50, props.pageSize());
    }

    @Test
    void zeroInitialDelay_isAccepted() {
        // Tests routinely set initial-delay-ms=0 so the first tick fires immediately.
        JournalVerificationProperties props = new JournalVerificationProperties(10_000L, 0L, 50);
        assertEquals(0L, props.initialDelayMs());
    }

    @Test
    void negativeInitialDelay_fallsBackToDefault() {
        JournalVerificationProperties props = new JournalVerificationProperties(10_000L, -1L, 50);
        assertEquals(5_000L, props.initialDelayMs());
    }
}
