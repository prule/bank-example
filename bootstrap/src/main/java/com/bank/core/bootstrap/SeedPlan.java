package com.bank.core.bootstrap;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SeedPlan represents the parsed and validated development database seeding plan configuration.
 */
public class SeedPlan {
    private String clearingAccountNumber;
    private BigDecimal clearingAccountOpeningBalance = new BigDecimal("100000.00");
    private List<CustomerSeed> customers = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (clearingAccountOpeningBalance == null || clearingAccountOpeningBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("clearing-account opening balance must be strictly positive — seeding exists to fund customers");
        }
    }

    public String getClearingAccountNumber() {
        return clearingAccountNumber;
    }

    public void setClearingAccountNumber(String clearingAccountNumber) {
        this.clearingAccountNumber = clearingAccountNumber;
    }

    public BigDecimal getClearingAccountOpeningBalance() {
        return clearingAccountOpeningBalance;
    }

    public void setClearingAccountOpeningBalance(BigDecimal clearingAccountOpeningBalance) {
        this.clearingAccountOpeningBalance = clearingAccountOpeningBalance;
    }

    public List<CustomerSeed> getCustomers() {
        return customers;
    }

    public void setCustomers(List<CustomerSeed> customers) {
        this.customers = customers;
    }

    public static record CustomerSeed(String number, BigDecimal openingBalance) {
        public CustomerSeed {
            if (number == null || number.trim().isEmpty()) {
                throw new IllegalArgumentException("Customer account number must not be empty");
            }
            if (openingBalance == null || openingBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Customer opening balance must be non-negative");
            }
        }
    }
}
