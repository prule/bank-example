package com.bank.core.infrastructure.scheduling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceDriftPropertiesTest {

    @Test
    void positiveValuesRoundTrip() {
        BalanceDriftProperties props = new BalanceDriftProperties(500L, 250L);
        assertEquals(500L, props.fixedDelayMs());
        assertEquals(250L, props.initialDelayMs());
    }

    @Test
    void zeroFixedDelayFallsBackTo30000() {
        BalanceDriftProperties props = new BalanceDriftProperties(0L, 100L);
        assertEquals(30_000L, props.fixedDelayMs());
    }

    @Test
    void negativeFixedDelayFallsBackTo30000() {
        BalanceDriftProperties props = new BalanceDriftProperties(-1L, 100L);
        assertEquals(30_000L, props.fixedDelayMs());
    }

    @Test
    void zeroInitialDelayIsAccepted() {
        BalanceDriftProperties props = new BalanceDriftProperties(500L, 0L);
        assertEquals(0L, props.initialDelayMs(),
                "tests need to be able to start the audit immediately");
    }

    @Test
    void negativeInitialDelayFallsBackTo15000() {
        BalanceDriftProperties props = new BalanceDriftProperties(500L, -1L);
        assertEquals(15_000L, props.initialDelayMs());
    }
}
