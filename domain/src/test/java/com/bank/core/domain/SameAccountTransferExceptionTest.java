package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SameAccountTransferExceptionTest {

    private static final AccountNumber A = AccountNumber.of("ACC-001");

    @Test
    void carriesAccountAndExtendsDomainException() {
        SameAccountTransferException ex = new SameAccountTransferException(A);

        assertInstanceOf(DomainException.class, ex);
        assertEquals(A, ex.account());
    }

    @Test
    void messageContainsAccountNumber() {
        SameAccountTransferException ex = new SameAccountTransferException(A);
        assertTrue(ex.getMessage().contains("ACC-001"), ex.getMessage());
    }

    @Test
    void rejectsNullAccount() {
        assertThrows(NullPointerException.class,
                () -> new SameAccountTransferException(null));
    }
}
