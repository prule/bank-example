package com.bank.core.infrastructure.concurrency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferLockingPropertiesTest {

    @Test
    void defaultStrategyIsJvm_whenNull() {
        TransferLockingProperties props = new TransferLockingProperties(5000L, null);
        assertEquals("jvm", props.strategy());
        assertEquals(5000L, props.lockWaitMs());
    }

    @Test
    void defaultStrategyIsJvm_whenBlank() {
        assertEquals("jvm", new TransferLockingProperties(500L, "").strategy());
        assertEquals("jvm", new TransferLockingProperties(500L, "   ").strategy());
    }

    @Test
    void jvmStrategy_caseInsensitive_normalisesToLowercase() {
        for (String v : new String[]{"jvm", "JVM", "Jvm", "jVm"}) {
            assertEquals("jvm", new TransferLockingProperties(5000L, v).strategy(),
                    "input: " + v);
        }
    }

    @Test
    void dbStrategy_caseInsensitive_normalisesToLowercase() {
        for (String v : new String[]{"db", "DB", "Db", "dB"}) {
            assertEquals("db", new TransferLockingProperties(5000L, v).strategy(),
                    "input: " + v);
        }
    }

    @Test
    void invalidStrategy_throwsWithDocumentedMessage() {
        for (String v : new String[]{"hybrid", "sql", "none", "redis", "database"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> new TransferLockingProperties(5000L, v),
                    "expected throw for input: " + v);
            assertTrue(ex.getMessage().contains("bank.transfer.locker must be 'jvm' or 'db' (was: '" + v + "')"),
                    "message should name the offending value verbatim; was: " + ex.getMessage());
        }
    }

    @Test
    void lockWaitMs_roundTripsAcrossAllValidStrategies() {
        assertEquals(1234L, new TransferLockingProperties(1234L, "jvm").lockWaitMs());
        assertEquals(1234L, new TransferLockingProperties(1234L, "db").lockWaitMs());
        assertEquals(1234L, new TransferLockingProperties(1234L, null).lockWaitMs());
    }
}
