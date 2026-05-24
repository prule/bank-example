package com.bank.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Currency-less monetary amount held at scale 2 with HALF_UP rounding. The
 * REQUIREMENTS document defines the first iteration as single-implicit-currency;
 * any future multi-currency change will need to touch every Money call site.
 */
public final class Money implements Comparable<Money> {

    static final int SCALE = 2;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount cannot be null");
        BigDecimal rescaled = amount.setScale(SCALE, ROUNDING);
        if (rescaled.signum() < 0) {
            throw new InvalidAmountException("amount cannot be negative: " + rescaled.toPlainString());
        }
        return new Money(rescaled);
    }

    public static Money of(String amount) {
        Objects.requireNonNull(amount, "amount cannot be null");
        return of(new BigDecimal(amount));
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        BigDecimal result = this.amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new InvalidAmountException(
                    "subtract would produce negative amount: " + this.amount.toPlainString()
                            + " - " + other.amount.toPlainString());
        }
        return new Money(result);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        return this.amount.compareTo(other.amount) > 0;
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Money that)) return false;
        return this.amount.compareTo(that.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
