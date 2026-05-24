package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountNumberTest {

    @Test
    void blankOrWhitespaceRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccountNumber.of(""));
        assertThrows(IllegalArgumentException.class, () -> AccountNumber.of("   "));
        assertThrows(NullPointerException.class, () -> AccountNumber.of(null));
    }

    @Test
    void validAccountNumberAccepted() {
        AccountNumber n = AccountNumber.of("ACC-001");
        assertEquals("ACC-001", n.value());
    }

    @Test
    void equalityIsCaseSensitive() {
        assertNotEquals(AccountNumber.of("ACC-001"), AccountNumber.of("acc-001"));
        assertEquals(AccountNumber.of("ACC-001"), AccountNumber.of("ACC-001"));
    }
}
