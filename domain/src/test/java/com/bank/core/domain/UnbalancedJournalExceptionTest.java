package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnbalancedJournalExceptionTest {

    @Test
    void carriesSumsAndExtendsDomainException() {
        UnbalancedJournalException ex = new UnbalancedJournalException(
                Money.of("9.99"), Money.of("10.00"));
        assertInstanceOf(DomainException.class, ex);
        assertEquals(Money.of("9.99"), ex.creditSum());
        assertEquals(Money.of("10.00"), ex.debitSum());
        assertTrue(ex.getMessage().contains("9.99"));
        assertTrue(ex.getMessage().contains("10.00"));
    }
}
