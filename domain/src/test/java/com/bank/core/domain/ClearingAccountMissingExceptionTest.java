package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearingAccountMissingExceptionTest {

    private static final AccountNumber CLEARING = AccountNumber.of("CLEARING-000");

    @Test
    void carriesClearingAccountNumberAndExtendsDomainException() {
        ClearingAccountMissingException ex = new ClearingAccountMissingException(CLEARING);

        assertInstanceOf(DomainException.class, ex);
        assertEquals(CLEARING, ex.clearingAccountNumber());
    }

    @Test
    void messageContainsClearingAccountNumber() {
        ClearingAccountMissingException ex = new ClearingAccountMissingException(CLEARING);
        assertTrue(ex.getMessage().contains("CLEARING-000"), ex.getMessage());
    }

    @Test
    void rejectsNullClearingAccountNumber() {
        assertThrows(NullPointerException.class,
                () -> new ClearingAccountMissingException(null));
    }
}
