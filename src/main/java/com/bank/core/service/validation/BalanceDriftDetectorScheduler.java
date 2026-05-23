package com.bank.core.service.validation;

import static com.bank.core.service.AccountOpeningService.CLEARING_ACCOUNT_NUM;

import com.bank.core.domain.Account;
import com.bank.core.domain.SystemConfig;
import com.bank.core.repository.AccountRepository;
import com.bank.core.repository.SystemConfigRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks that the balance recorded on the account matches the sum of the ledger transactions.
 * Accounts that don't balance will be suspended.
 */
@Component
public class BalanceDriftDetectorScheduler {

  private static final Logger log = LoggerFactory.getLogger(BalanceDriftDetectorScheduler.class);
  private static final Long SINGLETON_CONFIG_ID = 1L;

  private final AccountRepository accountRepository;
  private final SystemConfigRepository configRepository;

  public BalanceDriftDetectorScheduler(
      AccountRepository accountRepository, SystemConfigRepository configRepository) {
    this.accountRepository = accountRepository;
    this.configRepository = configRepository;
  }

  /**
   * DETERMINISTIC RANGE-LOCKED AUDIT LOOP Locks the processing segment between a baseline floor and
   * ceiling to prevent live race conditions.
   */
  @Scheduled(fixedDelay = 30000)
  @Transactional
  public void executeGlobalBalanceDriftAudit() {
    // Step 1: Fetch our persistent sequence floor from the database
    SystemConfig config =
        configRepository
            .findById(SINGLETON_CONFIG_ID)
            .orElseGet(() -> configRepository.saveAndFlush(SystemConfig.createDefault()));

    Long lastCheckedId = config.getLastBalanceCheckId();

    // Step 2: Capture the current ceiling marker right now
    Long currentMaxTxId = accountRepository.findMaxTransactionId().orElse(0L);

    // Safe short-circuit optimization: If no new logs have landed, exit early!
    if (currentMaxTxId <= lastCheckedId) {
      return;
    }

    log.info(
        "[Account Audit] Commencing range-locked scan. Segment: ID {} -> ID {}",
        lastCheckedId,
        currentMaxTxId);

    // Step 3: Run the incremental pass locked EXACTLY to our ceiling marker
    List<Account> driftedAccounts =
        accountRepository
            .findAccountsWithBalanceDriftInSegment(lastCheckedId, currentMaxTxId)
            .stream()
            .filter(account -> !account.getAccountNumber().equals(CLEARING_ACCOUNT_NUM))
            .toList();

    if (!driftedAccounts.isEmpty()) {
      log.error(
          "[Account Audit] CRITICAL INVARIANT VIOLATION: Detected {} drifted account(s)!",
          driftedAccounts.size());

      for (Account account : driftedAccounts) {
        log.warn(
            "[Account Audit] CONTAINER ISOLATION: Freezing account {}.",
            account.getAccountNumber());
        account.suspend();
        accountRepository.save(account);
      }
      accountRepository.flush();
    } else {
      log.info(
          "[Account Audit] Bounded range pass complete. Segment: ID {} -> ID {} matches history.",
          lastCheckedId,
          currentMaxTxId);
    }

    // Step 4: Advance our checkpoint exactly to our captured ceiling marker.
    // Any transaction that committed while the query was running will have an ID > currentMaxTxId
    // and will be picked up safely on the next loop iteration.
    config.updateLastBalanceCheckId(currentMaxTxId);
    configRepository.saveAndFlush(config);
  }
}
