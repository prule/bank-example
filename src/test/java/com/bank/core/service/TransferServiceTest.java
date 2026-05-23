package com.bank.core.service;

import static org.junit.jupiter.api.Assertions.*;

import com.bank.core.domain.Account;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "app.ledger.strategy=SYNCHRONOUS")
class TransferServiceTest {

  @Autowired private TransferService transferService;

  @Autowired private AccountRepository accountRepository;

  private String accountA;
  private String accountB;

  @BeforeEach
  void setUp() {
    accountRepository.deleteAll();

    Account a = new Account(UUID.randomUUID(), "ACC-1111", new BigDecimal("500.00"));
    Account b = new Account(UUID.randomUUID(), "ACC-2222", new BigDecimal("200.00"));

    accountRepository.save(a);
    accountRepository.save(b);

    accountA = a.getAccountNumber();
    accountB = b.getAccountNumber();
  }

  @Test
  void shouldSuccessfullyTransferFunds() {
    // Act
    transferService.transferFunds(accountA, accountB, new BigDecimal("150.00"));

    // Assert
    Account updatedA = accountRepository.findByAccountNumber(accountA).orElseThrow();
    Account updatedB = accountRepository.findByAccountNumber(accountB).orElseThrow();

    assertEquals(new BigDecimal("350.00"), updatedA.getBalance());
    assertEquals(new BigDecimal("350.00"), updatedB.getBalance());
  }

  @Test
  void shouldRollbackEntireTransactionWhenInsufficientFunds() {
    // Act & Assert
    assertThrows(
        InsufficientFundsException.class,
        () -> {
          transferService.transferFunds(accountA, accountB, new BigDecimal("600.00"));
        });

    // Verify state is completely untouched due to transactional rollback
    Account untouchedA = accountRepository.findByAccountNumber(accountA).orElseThrow();
    Account untouchedB = accountRepository.findByAccountNumber(accountB).orElseThrow();

    assertEquals(new BigDecimal("500.00"), untouchedA.getBalance());
    assertEquals(new BigDecimal("200.00"), untouchedB.getBalance());
  }
}
