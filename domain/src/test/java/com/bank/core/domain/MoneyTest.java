package com.bank.core.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

public class MoneyTest {

    @Test
    public void testScaleAndRounding() {
        Money m1 = Money.of("10");
        Money m2 = Money.of("10.00");
        Money m3 = Money.of("10.004");
        Money m4 = Money.of("10.005");

        assertEquals("10.00", m1.toString());
        assertEquals("10.00", m2.toString());
        assertEquals("10.00", m3.toString());
        assertEquals("10.01", m4.toString());
    }

    @Test
    public void testEqualityAndHashcode() {
        Money m1 = Money.of("10");
        Money m2 = Money.of("10.00");
        Money m3 = Money.of("10.0000");
        Money m4 = Money.of("10.01");

        assertEquals(m1, m2);
        assertEquals(m1, m3);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertEquals(m1.hashCode(), m3.hashCode());
        assertNotEquals(m1, m4);
    }

    @Test
    public void testCompareTo() {
        Money m1 = Money.of("10");
        Money m2 = Money.of("10.00");
        Money m3 = Money.of("10.01");
        Money m4 = Money.of("9.99");

        assertEquals(0, m1.compareTo(m2));
        assertTrue(m1.compareTo(m3) < 0);
        assertTrue(m1.compareTo(m4) > 0);

        assertTrue(m3.isGreaterThan(m1));
        assertTrue(m4.isLessThan(m1));
        assertFalse(m1.isZero());
        assertTrue(Money.ZERO.isZero());
    }

    @Test
    public void testNegativeAmountRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("-0.01"));
        assertThrows(IllegalArgumentException.class, () -> Money.of(new BigDecimal("-1.00")));
    }

    @Test
    public void testArithmetic() {
        Money ten = Money.of("10");
        Money five = Money.of("5");

        assertEquals(Money.of("15.00"), ten.plus(five));
        assertEquals(Money.of("5.00"), ten.minus(five));

        // Resulting negative should be rejected
        assertThrows(IllegalArgumentException.class, () -> five.minus(ten));
    }
}
