package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateAccountNumberExceptionTest {

    private static final AccountNumber N = AccountNumber.of("EXISTS-001");

    @Test
    void carriesNumberAndExtendsDomainException() {
        DuplicateAccountNumberException ex = new DuplicateAccountNumberException(N);

        assertInstanceOf(DomainException.class, ex);
        assertEquals(N, ex.number());
    }

    @Test
    void messageContainsAccountNumber() {
        DuplicateAccountNumberException ex = new DuplicateAccountNumberException(N);
        assertTrue(ex.getMessage().contains("EXISTS-001"), ex.getMessage());
    }

    @Test
    void rejectsNullNumber() {
        assertThrows(NullPointerException.class,
                () -> new DuplicateAccountNumberException(null));
    }
}
