package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import com.bank.core.infrastructure.persistence.account.AccountEntity;
import com.bank.core.infrastructure.persistence.account.AccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class JournalEntriesJpaAdapter implements JournalEntries {
    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;

    public JournalEntriesJpaAdapter(JournalEntryRepository journalEntryRepository, AccountRepository accountRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void save(JournalEntry journalEntry) {
        List<LedgerMovementEntity> movements = journalEntry.getMovements().stream()
                .map(m -> {
                    AccountEntity accountEntity = accountRepository.findById(m.accountId().toString())
                            .orElseThrow(() -> new IllegalArgumentException("Account not found for ID: " + m.accountId()));
                    return new LedgerMovementEntity(
                            accountEntity.getAccountNumber(),
                            m.amount().asBigDecimal(),
                            m.type(),
                            Instant.now()
                    );
                })
                .collect(Collectors.toList());

        JournalEntryEntity entity = new JournalEntryEntity(
                journalEntry.getId().toString(),
                journalEntry.getDescription(),
                journalEntry.getTimestamp(),
                journalEntry.getStatus(),
                movements
        );

        journalEntryRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findById(JournalEntryId id) {
        return journalEntryRepository.findById(id.toString())
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
        return journalEntryRepository.findByStatusOrderByTimestampAscIdAsc(status, PageRequest.of(0, limit)).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBalanced(JournalEntryId id) {
        if (!journalEntryRepository.existsById(id.toString())) {
            return false;
        }
        BigDecimal difference = journalEntryRepository.calculateBalanceDifference(id.toString());
        return difference != null && difference.compareTo(BigDecimal.ZERO) == 0;
    }

    private JournalEntry toDomain(JournalEntryEntity entity) {
        List<Movement> movements = entity.getMovements().stream()
                .map(me -> {
                    AccountEntity accountEntity = accountRepository.findByAccountNumber(me.getAccountNumber())
                            .orElseThrow(() -> new IllegalArgumentException("Account not found for number: " + me.getAccountNumber()));
                    return new Movement(
                            AccountId.fromString(accountEntity.getId()),
                            Money.of(me.getAmount()),
                            me.getType()
                    );
                })
                .collect(Collectors.toList());

        return JournalEntry.reconstitute(
                JournalEntryId.fromString(entity.getId()),
                entity.getDescription(),
                entity.getTimestamp(),
                entity.getStatus(),
                movements
        );
    }
}
