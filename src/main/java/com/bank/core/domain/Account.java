package com.bank.core.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
  @Id private UUID id;

  @Column(name = "account_number", unique = true, nullable = false)
  private String accountNumber;

  @Column(name = "balance", nullable = false)
  private BigDecimal balance;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private AccountStatus status;

  @Version private Long version;

  protected Account() {}

  public Account(UUID id, String accountNumber, BigDecimal initialBalance) {
    if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("Initial balance cannot be negative");
    }
    this.id = id;
    this.accountNumber = accountNumber;
    this.balance = initialBalance;
    this.status = AccountStatus.ACTIVE;
  }

  // --- Domain Business Invariants ---

  public void credit(BigDecimal amount) {
    ensureAccountIsActive();
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Credit amount must be greater than zero");
    }
    this.balance = this.balance.add(amount);
  }

  public void debit(BigDecimal amount) {
    ensureAccountIsActive();
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Debit amount must be greater than zero");
    }
    if (this.balance.subtract(amount).compareTo(BigDecimal.ZERO) <= 0) {
      throw new InsufficientFundsException(
          String.format(
              "Insufficient funds. Account %s has balance of %s, attempted debit of %s",
              this.accountNumber, this.balance, amount));
    }
    this.balance = this.balance.subtract(amount);
  }

  public void suspend() {
    if (this.status == AccountStatus.CLOSED) {
      throw new IllegalArgumentException("Account is closed");
    }
    this.status = AccountStatus.SUSPENDED;
  }

  public void reactivate() {
    if (this.status == AccountStatus.CLOSED) {
      throw new IllegalArgumentException("Account is closed");
    }
    this.status = AccountStatus.ACTIVE;
  }

  private void ensureAccountIsActive() {
    if (this.status != AccountStatus.ACTIVE) {
      throw new AccountInactiveException(
          "Account " + this.accountNumber + " is currently " + this.status);
    }
  }

  // --- Strict Getters (No Setters allowed) ---
  public UUID getId() {
    return id;
  }

  public String getAccountNumber() {
    return accountNumber;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public AccountStatus getStatus() {
    return status;
  }

  public Long getVersion() {
    return version;
  }

  @Override
  public String toString() {
    return "Account{"
        + "id="
        + id
        + ", accountNumber='"
        + accountNumber
        + '\''
        + ", balance="
        + balance
        + ", status="
        + status
        + ", version="
        + version
        + '}';
  }
}
