package com.bank.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bank.core.domain.Account;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalStatus;
import com.bank.core.domain.TransactionType;
import com.bank.core.repository.AccountRepository;
import com.bank.core.repository.JournalEntryRepository;
import com.bank.core.service.validation.LedgerReconciliationScheduler;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@TestPropertySource(properties = "app.ledger.strategy=BATCH_RECONCILIATION")
@Transactional
class BatchStrategyTest {

  @Autowired private JournalEntryRepository journalRepository;

  @Autowired private AccountRepository accountRepository;

  @Autowired private LedgerReconciliationScheduler scheduler;

  @Test
  void shouldAllowPendingSavesAndVerifyThemOnSchedulerSweepPass() {
    var source =
        accountRepository.save(new Account(UUID.randomUUID(), "SRC-B", new BigDecimal("500.00")));
    var dest =
        accountRepository.save(new Account(UUID.randomUUID(), "DEST-B", new BigDecimal("500.00")));

    JournalEntry pendingJournal = new JournalEntry("Batch Test Transfer");
    pendingJournal.addLeg(source.getId(), new BigDecimal("100.00"), TransactionType.DEBIT);
    pendingJournal.addLeg(dest.getId(), new BigDecimal("100.00"), TransactionType.CREDIT);

    JournalEntry saved = journalRepository.saveAndFlush(pendingJournal);
    assertEquals(JournalStatus.PENDING, saved.getStatus());

    // Act: Manually fire the scheduler's sweep loop
    scheduler.executeGlobalDatabaseAuditSweep();

    // Assert: Confirm the status was updated to VERIFIED using the database native query math pass
    JournalEntry processedJournal = journalRepository.findById(saved.getId()).orElseThrow();
    assertEquals(JournalStatus.VERIFIED, processedJournal.getStatus());
  }
}
