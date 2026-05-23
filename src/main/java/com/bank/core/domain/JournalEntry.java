package com.bank.core.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {
  private static final Logger log = LoggerFactory.getLogger(JournalEntry.class);

  @Id private UUID id;

  @Column(nullable = false)
  private String description;

  @Column(nullable = false)
  private Instant timestamp;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @JoinColumn(name = "journal_entry_id")
  private List<LedgerTransaction> transactions = new ArrayList<>();

  @Enumerated(EnumType.STRING)
  private JournalStatus status = JournalStatus.PENDING;

  protected JournalEntry() {}

  public JournalEntry(String description) {
    this.id = UUID.randomUUID();
    this.description = description;
    this.timestamp = Instant.now();
  }

  public void addLeg(UUID accountId, BigDecimal amount, TransactionType type) {
    this.transactions.add(new LedgerTransaction(accountId, amount, type));
  }

  public void markVerified() {
    this.status = JournalStatus.VERIFIED;
  }

  public void markFailed() {
    this.status = JournalStatus.FAILED;
  }

  public List<LedgerTransaction> getTransactions() {
    return transactions;
  }

  public JournalStatus getStatus() {
    return status;
  }

  public UUID getId() {
    return id;
  }

  @Override
  public String toString() {
    return "JournalEntry{"
        + "id="
        + id
        + ", description='"
        + description
        + '\''
        + ", timestamp="
        + timestamp
        + ", status="
        + status
        + '}';
  }
}
