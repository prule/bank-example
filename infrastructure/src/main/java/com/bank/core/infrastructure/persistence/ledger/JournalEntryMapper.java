package com.bank.core.infrastructure.persistence.ledger;

import com.bank.core.domain.AccountId;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.JournalEntryId;
import com.bank.core.domain.Money;
import com.bank.core.domain.Movement;

import java.util.List;

final class JournalEntryMapper {

    private JournalEntryMapper() {
        // utility
    }

    static JournalEntryEntity toEntity(JournalEntry domain) {
        JournalEntryEntity entity = new JournalEntryEntity(
                domain.id().value(),
                domain.description(),
                domain.timestamp(),
                domain.status());
        for (Movement movement : domain.movements()) {
            LedgerMovementEntity movementEntity = new LedgerMovementEntity(
                    movement.accountId().value(),
                    movement.amount().toBigDecimal(),
                    movement.type());
            entity.addMovement(movementEntity);
        }
        return entity;
    }

    static JournalEntry toDomain(JournalEntryEntity entity) {
        List<Movement> movements = entity.getMovements().stream()
                .map(m -> new Movement(
                        AccountId.of(m.getAccountId()),
                        Money.of(m.getAmount()),
                        m.getMovementType()))
                .toList();
        return JournalEntry.rehydrate(
                JournalEntryId.of(entity.getId()),
                entity.getDescription(),
                entity.getTimestamp(),
                entity.getVerificationStatus(),
                movements);
    }
}
