package com.bank.core.event;

import com.bank.core.domain.JournalEntry;
import org.springframework.context.ApplicationEvent;

public class JournalCreatedEvent extends ApplicationEvent {
  private final JournalEntry journalEntry;

  public JournalCreatedEvent(Object source, JournalEntry journalEntry) {
    super(source);
    this.journalEntry = journalEntry;
  }

  public JournalEntry getJournalEntry() {
    return journalEntry;
  }
}
