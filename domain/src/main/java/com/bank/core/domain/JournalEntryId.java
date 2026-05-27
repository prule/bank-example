package com.bank.core.domain;

import java.util.Objects;
import java.util.UUID;

public final class JournalEntryId {
    private final UUID value;

    public JournalEntryId(UUID value) {
        this.value = Objects.requireNonNull(value, "Value must not be null");
    }

    public static JournalEntryId generate() {
        return new JournalEntryId(UUID.randomUUID());
    }

    public static JournalEntryId fromString(String str) {
        Objects.requireNonNull(str, "String must not be null");
        return new JournalEntryId(UUID.fromString(str));
    }

    public UUID getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JournalEntryId)) return false;
        JournalEntryId that = (JournalEntryId) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
