package com.bank.core.service.validation;

import com.bank.core.domain.JournalEntry;
import com.bank.core.event.JournalCreatedEvent;
import com.bank.core.repository.JournalEntryRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * This will receive JournalCreatedEvent events after they are committed and invoke the ledger
 * check.
 */
@Component
@ConditionalOnProperty(value = "app.features.ledger-immediate-verify", havingValue = "true")
public class TransactionTriggeredJournalVerification {
  private static final Logger log =
      LoggerFactory.getLogger(TransactionTriggeredJournalVerification.class);

  private final JournalEntryRepository journalEntryRepository;
  private final LedgerAuditorService ledgerAuditorService;

  public TransactionTriggeredJournalVerification(
      JournalEntryRepository journalEntryRepository, LedgerAuditorService ledgerAuditorService) {
    this.journalEntryRepository = journalEntryRepository;
    this.ledgerAuditorService = ledgerAuditorService;
  }

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onJournalCreated(JournalCreatedEvent event) {
    log.debug("JournalCreatedEvent received {}", event);
    UUID journalId = event.getJournalEntry().getId();

    JournalEntry journal = journalEntryRepository.findById(journalId).orElseThrow();

    ledgerAuditorService.verifyJournal(journal);
  }
}
