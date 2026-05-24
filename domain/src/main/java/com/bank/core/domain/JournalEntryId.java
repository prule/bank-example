package com.bank.core.domain;

import java.util.Objects;
import java.util.UUID;

public record JournalEntryId(UUID value) {

    public JournalEntryId {
        Objects.requireNonNull(value, "journal entry id cannot be null");
    }

    public static JournalEntryId generate() {
        return new JournalEntryId(UUID.randomUUID());
    }

    public static JournalEntryId of(UUID value) {
        return new JournalEntryId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
