package com.bank.core.domain;

public final class UnbalancedJournalException extends DomainException {

    private final Money creditSum;
    private final Money debitSum;

    public UnbalancedJournalException(Money creditSum, Money debitSum) {
        super("Journal does not balance: credit sum " + creditSum + " != debit sum " + debitSum);
        this.creditSum = creditSum;
        this.debitSum = debitSum;
    }

    public Money creditSum() {
        return creditSum;
    }

    public Money debitSum() {
        return debitSum;
    }
}
