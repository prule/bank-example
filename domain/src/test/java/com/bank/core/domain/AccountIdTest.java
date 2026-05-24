package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountIdTest {

    @Test
    void generateReturnsNonNullDistinctIds() {
        AccountId a = AccountId.generate();
        AccountId b = AccountId.generate();
        assertNotNull(a.value());
        assertNotNull(b.value());
        assertNotEquals(a, b);
    }

    @Test
    void ofNullThrows() {
        assertThrows(NullPointerException.class, () -> AccountId.of(null));
    }

    @Test
    void equalityFollowsUuid() {
        UUID uuid = UUID.randomUUID();
        assertEquals(AccountId.of(uuid), AccountId.of(uuid));
        assertEquals(AccountId.of(uuid).hashCode(), AccountId.of(uuid).hashCode());
    }
}
