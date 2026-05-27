package com.bank.core.domain;

public final class UnbalancedJournalException extends DomainException {
    private final Money creditSum;
    private final Money debitSum;

    public UnbalancedJournalException(Money creditSum, Money debitSum) {
        super(String.format("Journal entry is unbalanced. Credits: %s, Debits: %s", creditSum, debitSum));
        this.creditSum = creditSum;
        this.debitSum = debitSum;
    }

    public Money getCreditSum() {
        return creditSum;
    }

    public Money getDebitSum() {
        return debitSum;
    }
}
