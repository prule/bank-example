package com.bank.core.service.validation;

import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalStatus;
import com.bank.core.repository.JournalEntryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** This will look for pending journals and invoke the ledger check as per the schedule. */
@Component
public class LedgerReconciliationScheduler {

  private static final Logger log = LoggerFactory.getLogger(LedgerReconciliationScheduler.class);

  private final JournalEntryRepository journalRepository;
  private final LedgerAuditorService ledgerAuditorService;

  public LedgerReconciliationScheduler(
      JournalEntryRepository journalRepository, LedgerAuditorService ledgerAuditorService) {
    this.journalRepository = journalRepository;
    this.ledgerAuditorService = ledgerAuditorService;
  }

  @Scheduled(fixedDelay = 10000)
  @Transactional
  public void executeGlobalDatabaseAuditSweep() {
    log.info("[LEDGER CHECK] Starting database-driven ledger reconciliation batch sweep...");

    // Fetch pending journals using a lightweight, unjoined query page
    List<JournalEntry> pendingJournals =
        journalRepository.findByStatus(JournalStatus.PENDING, PageRequest.of(0, 50));

    for (JournalEntry journal : pendingJournals) {
      ledgerAuditorService.verifyJournal(journal);
    }

    log.info(
        "[LEDGER CHECK] Completed database-driven ledger reconciliation batch sweep... {}",
        pendingJournals.size());
  }
}
