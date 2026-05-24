package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceNotFoundExceptionTest {

    @Test
    void carriesResourceTypeAndIdentifierAndExtendsDomainException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("account", "ACC-001");

        assertInstanceOf(DomainException.class, ex);
        assertEquals("account", ex.resourceType());
        assertEquals("ACC-001", ex.identifier());
    }

    @Test
    void messageContainsBothFields() {
        ResourceNotFoundException ex = new ResourceNotFoundException("journal", "abc-123");
        String message = ex.getMessage();

        assertTrue(message.contains("journal"), message);
        assertTrue(message.contains("abc-123"), message);
    }

    @Test
    void rejectsNullResourceType() {
        assertThrows(NullPointerException.class,
                () -> new ResourceNotFoundException(null, "ACC-001"));
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(NullPointerException.class,
                () -> new ResourceNotFoundException("account", null));
    }
}
