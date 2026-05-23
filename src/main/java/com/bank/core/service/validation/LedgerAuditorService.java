package com.bank.core.service.validation;

import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.JournalEntry;
import com.bank.core.repository.AccountRepository;
import com.bank.core.repository.JournalEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checks that a journal entry is balanced.
 *
 * <p>If the journal transactions sum to zero the journal entry is marked VERIFIED. Otherwise, it
 * will be marked FAILED and the accounts associated with this journal entry will be suspended
 * pending investigation.
 */
@Service
public class LedgerAuditorService {

  private static final Logger log = LoggerFactory.getLogger(LedgerAuditorService.class);

  private final JournalEntryRepository journalRepository;
  private final AccountRepository accountRepository;

  public LedgerAuditorService(
      JournalEntryRepository journalRepository, AccountRepository accountRepository) {
    this.journalRepository = journalRepository;
    this.accountRepository = accountRepository;
  }

  /** Single point to verify a journal entry and take action if it fails. */
  public boolean verifyJournal(JournalEntry journal) {
    // Execute unified database query check
    boolean isBalanced = journalRepository.isJournalBalancedInDatabase(journal.getId()) == 1;

    if (isBalanced) {
      journal.markVerified();
      journalRepository.saveAndFlush(journal);
      log.info("Journal entry validated. ID: {}.", journal.getId());
      return true;
    } else {
      // STEP 1: Fail the journal state flag
      journal.markFailed();
      journalRepository.saveAndFlush(journal);

      // CRITICAL AUDIT ALERT - Would hook into real security dashboards
      log.error(
          "[SECURITY BREACH ALARM] MALFORMED JOURNAL ENTRY DETECTED! ID: {}. Accounting debits do not equal credits.",
          journal.getId());

      // STEP 2: Iterate through the legs and quarantine the target accounts
      journal
          .getTransactions()
          .forEach(
              transaction -> {
                accountRepository
                    .findById(transaction.getAccountId())
                    .ifPresent(
                        account -> {
                          if (account.getStatus() != AccountStatus.SUSPENDED) {
                            log.warn(
                                "[EMERGENCY CONTAINMENT] Freezing compromised account reference {} linked to malformed journal {}.",
                                account.getAccountNumber(),
                                journal.getId());

                            // Force the entity into suspension to block further data modifications
                            account.suspend();
                            accountRepository.saveAndFlush(account);
                          }
                        });
              });

      return false;
    }
  }
}
