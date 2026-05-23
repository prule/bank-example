package com.bank.core.service.validation;

import com.bank.core.domain.Account;
import com.bank.core.domain.JournalEntry;

public interface LedgerValidator {
  /** Executes double-entry bookkeeping validation based on the active strategy. */
  void validate(Account source, Account destination, JournalEntry journal);
}
