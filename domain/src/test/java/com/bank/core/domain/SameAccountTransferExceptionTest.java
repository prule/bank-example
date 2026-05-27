package com.bank.core.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SameAccountTransferExceptionTest {

    @Test
    public void testExceptionProperties() {
        SameAccountTransferException ex = new SameAccountTransferException("ACC-123");
        assertEquals("ACC-123", ex.account());
        assertTrue(ex.getMessage().contains("ACC-123"));
        assertTrue(ex instanceof DomainException);
    }
}
