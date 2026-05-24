package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.VerificationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
class JournalEntriesJpaAdapter implements JournalEntries {

    private final JournalEntryRepository repository;

    JournalEntriesJpaAdapter(JournalEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(JournalEntry entry) {
        JournalEntryEntity existing = repository.findById(entry.id().value()).orElse(null);
        if (existing == null) {
            repository.save(JournalEntryMapper.toEntity(entry));
        } else {
            // Status-only update: append-only requirement forbids touching
            // any other column once persisted.
            existing.setVerificationStatus(entry.status());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findById(JournalEntryId id) {
        return repository.findById(id.value()).map(JournalEntryMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
        return repository.findByStatusOrdered(status, PageRequest.of(0, limit)).stream()
                .map(JournalEntryMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBalanced(JournalEntryId id) {
        if (!repository.existsById(id.value())) {
            return false;
        }
        BigDecimal sum = repository.sumSignedAmount(id.value());
        return sum != null && sum.signum() == 0;
    }
}
