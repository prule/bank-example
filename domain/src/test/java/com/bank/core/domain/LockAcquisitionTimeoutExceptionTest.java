package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockAcquisitionTimeoutExceptionTest {

    private static final AccountNumber A = AccountNumber.of("AAA");
    private static final AccountNumber B = AccountNumber.of("BBB");

    @Test
    void carriesAccountsAndWaitAndExtendsDomainException() {
        LockAcquisitionTimeoutException ex = new LockAcquisitionTimeoutException(A, B, 500);

        assertInstanceOf(DomainException.class, ex);
        assertSame(A, ex.firstAccount());
        assertSame(B, ex.secondAccount());
        assertEquals(500L, ex.waitMs());
        assertNull(ex.getCause());
    }

    @Test
    void messageIdentifiesBothAccountsAndElapsedWait() {
        LockAcquisitionTimeoutException ex = new LockAcquisitionTimeoutException(A, B, 1234);
        String message = ex.getMessage();

        assertTrue(message.contains("AAA"), message);
        assertTrue(message.contains("BBB"), message);
        assertTrue(message.contains("1234"), message);
    }

    @Test
    void preservesCauseWhenSupplied() {
        InterruptedException cause = new InterruptedException("interrupted");
        LockAcquisitionTimeoutException ex = new LockAcquisitionTimeoutException(A, B, 0, cause);

        assertSame(cause, ex.getCause());
        assertEquals(0L, ex.waitMs());
    }

    @Test
    void rejectsNullAccountArguments() {
        assertThrows(NullPointerException.class,
                () -> new LockAcquisitionTimeoutException(null, B, 500));
        assertThrows(NullPointerException.class,
                () -> new LockAcquisitionTimeoutException(A, null, 500));
    }
}
