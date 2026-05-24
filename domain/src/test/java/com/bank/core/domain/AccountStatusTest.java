package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountStatusTest {

    @Test
    void isActive() {
        assertTrue(AccountStatus.ACTIVE.isActive());
        assertFalse(AccountStatus.SUSPENDED.isActive());
        assertFalse(AccountStatus.CLOSED.isActive());
    }

    @Test
    void isClosed() {
        assertFalse(AccountStatus.ACTIVE.isClosed());
        assertFalse(AccountStatus.SUSPENDED.isClosed());
        assertTrue(AccountStatus.CLOSED.isClosed());
    }
}
