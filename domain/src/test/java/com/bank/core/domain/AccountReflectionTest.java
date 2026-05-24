package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the spec scenario "No path mutates balance outside credit/debit". If any
 * future change adds a public setter or a non-private constructor to Account,
 * this test fails.
 */
class AccountReflectionTest {

    @Test
    void onlyPrivateConstructorsExist() {
        for (Constructor<?> c : Account.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(c.getModifiers()),
                    "Account constructor must be private (factory only): " + c);
        }
    }

    @Test
    void noSetterMethodsOnAccount() {
        boolean anySetter = Arrays.stream(Account.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().startsWith("set"));
        assertFalse(anySetter, "Account must not expose any set* method");
    }

    @Test
    void identityFieldsAreFinal() throws NoSuchFieldException {
        assertTrue(Modifier.isFinal(field("id").getModifiers()), "id must be final");
        assertTrue(Modifier.isFinal(field("number").getModifiers()), "number must be final");
    }

    @Test
    void balanceAndStatusAreNonFinalAndPrivate() throws NoSuchFieldException {
        Field balance = field("balance");
        Field status = field("status");
        assertFalse(Modifier.isFinal(balance.getModifiers()), "balance must be non-final (mutators set it)");
        assertFalse(Modifier.isFinal(status.getModifiers()), "status must be non-final");
        assertTrue(Modifier.isPrivate(balance.getModifiers()), "balance must be private");
        assertTrue(Modifier.isPrivate(status.getModifiers()), "status must be private");
    }

    @Test
    void mutatorMethodsArePublic() {
        for (String name : new String[]{"credit", "debit", "suspend", "reactivate"}) {
            assertTrue(Arrays.stream(Account.class.getDeclaredMethods())
                            .anyMatch(m -> m.getName().equals(name) && Modifier.isPublic(m.getModifiers())),
                    "Account must expose a public " + name + " method");
        }
    }

    @Test
    void accountClassIsFinal() {
        assertTrue(Modifier.isFinal(Account.class.getModifiers()),
                "Account must be final so subclassing cannot bypass invariants");
    }

    private static Field field(String name) throws NoSuchFieldException {
        return Account.class.getDeclaredField(name);
    }
}
