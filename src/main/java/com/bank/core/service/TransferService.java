package com.bank.core.service;

import com.bank.core.domain.Account;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.TransactionType;
import com.bank.core.event.JournalCreatedEvent;
import com.bank.core.repository.AccountRepository;
import com.bank.core.repository.JournalEntryRepository;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  private final DeterministicAccountLockService deterministicAccountLockService;
  private final AccountRepository accountRepository;
  private final JournalEntryRepository journalRepository;
  private final ApplicationEventPublisher eventPublisher;

  public TransferService(
      DeterministicAccountLockService deterministicAccountLockService,
      AccountRepository accountRepository,
      JournalEntryRepository journalRepository,
      ApplicationEventPublisher eventPublisher) {
    this.deterministicAccountLockService = deterministicAccountLockService;
    this.accountRepository = accountRepository;
    this.journalRepository = journalRepository;
    this.eventPublisher = eventPublisher;
  }

  /**
   * This is where the transfer happens. The account balances are updated and journal entry is
   * created. Separate from this there are different strategies for double checking balances and
   * ledgers.
   *
   * @param sourceAccountNumber
   * @param destinationAccountNumber
   * @param amount
   */
  @Transactional
  public void transferFunds(
      String sourceAccountNumber, String destinationAccountNumber, BigDecimal amount) {

    AccountPair accountPair =
        deterministicAccountLockService.lock(sourceAccountNumber, destinationAccountNumber);
    Account sourceAccount = accountPair.getSourceAccount();
    Account destinationAccount = accountPair.getDestinationAccount();

    sourceAccount.debit(amount);
    destinationAccount.credit(amount);

    JournalEntry journal =
        new JournalEntry(
            "Fund Transfer: " + sourceAccountNumber + " to " + destinationAccountNumber);

    journal.addLeg(sourceAccount.getId(), amount, TransactionType.DEBIT);
    journal.addLeg(destinationAccount.getId(), amount, TransactionType.CREDIT);

    accountRepository.save(sourceAccount);
    accountRepository.save(destinationAccount);
    JournalEntry savedJournal = journalRepository.save(journal);

    eventPublisher.publishEvent(new JournalCreatedEvent(this, savedJournal));

    log.info(
        "Transferred {} from {} to {} with journal {}.",
        amount,
        sourceAccountNumber,
        destinationAccountNumber,
        savedJournal);
  }
}
