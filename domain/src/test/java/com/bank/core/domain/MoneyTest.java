package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void ofNullThrows() {
        assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null));
        assertThrows(NullPointerException.class, () -> Money.of((String) null));
    }

    @Test
    void ofNegativeThrows() {
        InvalidAmountException ex = assertThrows(InvalidAmountException.class,
                () -> Money.of(new BigDecimal("-0.01")));
        assertTrue(ex.getMessage().contains("negative"));
    }

    @Test
    void rescaledToTwoDecimalPlacesHalfUp() {
        assertEquals("10.01", Money.of("10.005").toString());
        assertEquals("10.00", Money.of("10.004").toString());
    }

    @Test
    void equalsIgnoresTrailingZeros() {
        assertEquals(Money.of("10.00"), Money.of("10"));
        assertEquals(Money.of("10.00").hashCode(), Money.of("10").hashCode());
    }

    @Test
    void compareToWorks() {
        assertTrue(Money.of("5.00").compareTo(Money.of("10.00")) < 0);
        assertTrue(Money.of("10.00").compareTo(Money.of("5.00")) > 0);
        assertEquals(0, Money.of("10.00").compareTo(Money.of("10")));
    }

    @Test
    void addAndSubtract() {
        assertEquals(Money.of("15.00"), Money.of("10.00").add(Money.of("5.00")));
        assertEquals(Money.of("5.00"), Money.of("10.00").subtract(Money.of("5.00")));
    }

    @Test
    void subtractWouldGoNegativeThrows() {
        InvalidAmountException ex = assertThrows(InvalidAmountException.class,
                () -> Money.of("5.00").subtract(Money.of("10.00")));
        assertTrue(ex.getMessage().contains("negative"));
    }

    @Test
    void isZero() {
        assertTrue(Money.ZERO.isZero());
        assertTrue(Money.of("0").isZero());
        assertFalse(Money.of("0.01").isZero());
    }

    @Test
    void isGreaterThanStrictlyGreater() {
        assertTrue(Money.of("10.01").isGreaterThan(Money.of("10.00")));
        assertFalse(Money.of("10.00").isGreaterThan(Money.of("10.00")));
        assertFalse(Money.of("9.99").isGreaterThan(Money.of("10.00")));
    }

    @Test
    void toStringIsCanonicalScale2() {
        assertEquals("10.00", Money.of("10").toString());
        assertEquals("0.00", Money.ZERO.toString());
    }

    @Test
    void addNullThrows() {
        assertThrows(NullPointerException.class, () -> Money.of("10").add(null));
    }
}
