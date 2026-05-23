package com.bank.core.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "system_config")
public class SystemConfig {

  @Id private Long id = 1L; // Lock the configuration to a single-row pattern

  @Column(name = "last_balance_check_id", nullable = false)
  private Long lastBalanceCheckId = 0L;

  // Standard default constructor for JPA reflection
  protected SystemConfig() {}

  public static SystemConfig createDefault() {
    SystemConfig config = new SystemConfig();
    config.lastBalanceCheckId = 0L;
    return config;
  }

  public Long getId() {
    return id;
  }

  public Long getLastBalanceCheckId() {
    return lastBalanceCheckId;
  }

  public void updateLastBalanceCheckId(Long newId) {
    this.lastBalanceCheckId = newId;
  }
}
