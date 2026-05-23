package com.bank.core.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransactionType type;

  protected LedgerTransaction() {}

  public LedgerTransaction(UUID accountId, BigDecimal amount, TransactionType type) {
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Ledger transaction amount must be positive.");
    }
    this.accountId = accountId;
    this.amount = amount;
    this.type = type;
  }

  // Strict getters, absolutely NO setters. This data is write-once.
  public UUID getAccountId() {
    return accountId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public TransactionType getType() {
    return type;
  }

  public Long getId() {
    return id;
  }
}
