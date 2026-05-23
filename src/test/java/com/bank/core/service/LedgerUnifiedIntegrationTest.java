package com.bank.core.service;

import static org.junit.jupiter.api.Assertions.*;

import com.bank.core.domain.*;
import com.bank.core.repository.AccountRepository;
import com.bank.core.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@TestPropertySource(
    properties =
        "app.ledger.strategy=SYNCHRONOUS") // Explicitly binds a strategy to this test context
@Transactional // Rolls back changes automatically between test runs
class LedgerUnifiedIntegrationTest {

  @Autowired private TransferService transferService;

  @Autowired private AccountRepository accountRepository;

  @Autowired private JournalEntryRepository journalRepository;

  @Autowired private ApplicationContext applicationContext;

  private Account sourceAccount;
  private Account destinationAccount;

  @BeforeEach
  void setUp() {
    journalRepository.deleteAll();
    accountRepository.deleteAll();

    sourceAccount =
        accountRepository.saveAndFlush(
            new Account(UUID.randomUUID(), "ACC-1111", new BigDecimal("1000.00")));
    destinationAccount =
        accountRepository.saveAndFlush(
            new Account(UUID.randomUUID(), "ACC-2222", new BigDecimal("500.00")));
  }

  @Test
  void shouldVerifyDatabaseLevelCheckReturnsCorrectStatus() {
    // Arrange: Build a perfectly balanced journal entry directly
    JournalEntry cleanJournal = new JournalEntry("Balanced Test Journal");
    cleanJournal.addLeg(sourceAccount.getId(), new BigDecimal("150.00"), TransactionType.DEBIT);
    cleanJournal.addLeg(
        destinationAccount.getId(), new BigDecimal("150.00"), TransactionType.CREDIT);
    journalRepository.saveAndFlush(cleanJournal);

    // Act: Run the core unified database calculation query
    int cleanCheckResult = journalRepository.isJournalBalancedInDatabase(cleanJournal.getId());

    // Arrange: Build an unbalanced journal entry directly
    JournalEntry brokenJournal = new JournalEntry("Broken Test Journal");
    brokenJournal.addLeg(sourceAccount.getId(), new BigDecimal("500.00"), TransactionType.DEBIT);
    journalRepository.saveAndFlush(brokenJournal);

    int brokenCheckResult = journalRepository.isJournalBalancedInDatabase(brokenJournal.getId());

    // Assert: Verify database engine arithmetic results
    assertEquals(1, cleanCheckResult, "Database should report a balanced journal as 1");
    assertEquals(0, brokenCheckResult, "Database should report an unbalanced journal as 0");
  }

  @Test
  void shouldSuccessfullyProcessStandardTransferWorkflow() {
    // Act: Run a standard transfer through our orchestrator service layer
    // This will invoke whichever strategy is active in your test application profile config
    transferService.transferFunds(
        sourceAccount.getAccountNumber(),
        destinationAccount.getAccountNumber(),
        new BigDecimal("200.00"));

    // Assert: Confirm snapshot balances updated immediately regardless of strategy
    Account updatedSource = accountRepository.findById(sourceAccount.getId()).orElseThrow();
    Account updatedDest = accountRepository.findById(destinationAccount.getId()).orElseThrow();

    assertEquals(new BigDecimal("800.00"), updatedSource.getBalance());
    assertEquals(new BigDecimal("700.00"), updatedDest.getBalance());

    // Assert: Confirm a journal entry was successfully saved in the database
    var journals = journalRepository.findAll();
    assertEquals(1, journals.size());
  }
}
