package com.bank.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bank.core.domain.Account;
import com.bank.core.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "app.ledger.strategy=SYNCHRONOUS")
class TransferConcurrencyTest {

  @Autowired private TransferService transferService;

  @Autowired private AccountRepository accountRepository;

  private String accountA;
  private String accountB;

  @BeforeEach
  void setUp() {
    accountRepository.deleteAll();

    // Seed two accounts with initial balances
    Account a = new Account(UUID.randomUUID(), "ACC-1111", new BigDecimal("1000.00"));
    Account b = new Account(UUID.randomUUID(), "ACC-2222", new BigDecimal("1000.00"));

    accountRepository.save(a);
    accountRepository.save(b);

    accountA = a.getAccountNumber();
    accountB = b.getAccountNumber();
  }

  @Test
  void shouldHandleIntenseConcurrentTransfersWithoutDeadlocksOrDataLoss()
      throws InterruptedException {
    int numberOfThreads = 100;
    BigDecimal transferAmount = new BigDecimal("10.00");

    ExecutorService executorService = Executors.newFixedThreadPool(32);

    // This latch blocks all threads until they are all initialized, forcing them to fire at once
    CountDownLatch startLatch = new CountDownLatch(1);

    // This latch tracks when all threads have finished executing
    CountDownLatch finishLatch = new CountDownLatch(numberOfThreads);

    for (int i = 0; i < numberOfThreads; i++) {
      final int index = i;
      executorService.submit(
          () -> {
            try {
              // Wait at the starting gate
              startLatch.await();

              // Alternate directions to create maximum contention and test deadlock prevention
              if (index % 2 == 0) {
                transferService.transferFunds(accountA, accountB, transferAmount);
              } else {
                transferService.transferFunds(accountB, accountA, transferAmount);
              }
            } catch (Exception e) {
              System.err.println("Transfer execution failed: " + e.getMessage());
            } finally {
              finishLatch.countDown();
            }
          });
    }

    // Act: Open the starting gate! All 100 threads hit the service simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();

    // Wait up to 10 seconds for all threads to wrap up
    boolean completedTimely = finishLatch.await(10, TimeUnit.SECONDS);
    executorService.shutdown();

    long duration = System.currentTimeMillis() - startTime;
    System.out.println(
        "Executed " + numberOfThreads + " concurrent transfers in " + duration + "ms");

    // Assert
    assertEquals(
        true, completedTimely, "The test timed out! This usually means a deadlock occurred.");

    Account updatedA = accountRepository.findByAccountNumber(accountA).orElseThrow();
    Account updatedB = accountRepository.findByAccountNumber(accountB).orElseThrow();

    // Since 50 threads moved $10 from A->B, and 50 threads moved $10 from B->A,
    // the net balance modifications should perfectly cancel out.
    assertEquals(
        new BigDecimal("1000.00"), updatedA.getBalance(), "Account A suffered data corruption!");
    assertEquals(
        new BigDecimal("1000.00"), updatedB.getBalance(), "Account B suffered data corruption!");
  }
}
