package com.bank.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalEntryTest {

    private static final AccountId SOURCE = AccountId.generate();
    private static final AccountId DEST = AccountId.generate();
    private static final Instant NOW = Instant.parse("2026-05-24T01:00:00Z");

    @Test
    void factoryWithOneMovementRejected() {
        List<Movement> only = List.of(new Movement(SOURCE, Money.of("10.00"), MovementType.DEBIT));
        assertThrows(IllegalArgumentException.class,
                () -> JournalEntry.create("solo", NOW, only));
    }

    @Test
    void factoryWithUnbalancedMovementsRejected() {
        List<Movement> unbalanced = List.of(
                new Movement(SOURCE, Money.of("10.00"), MovementType.DEBIT),
                new Movement(DEST, Money.of("9.99"), MovementType.CREDIT));
        UnbalancedJournalException ex = assertThrows(UnbalancedJournalException.class,
                () -> JournalEntry.create("mismatch", NOW, unbalanced));
        assertEquals(Money.of("9.99"), ex.creditSum());
        assertEquals(Money.of("10.00"), ex.debitSum());
    }

    @Test
    void balancedFactoryReturnsPending() {
        JournalEntry je = balanced();
        assertEquals(VerificationStatus.PENDING, je.status());
        assertNotNull(je.id());
        assertEquals(NOW, je.timestamp());
        assertEquals("transfer", je.description());
        assertEquals(2, je.movements().size());
    }

    @Test
    void markVerifiedFromPendingTransitions() {
        JournalEntry je = balanced();
        je.markVerified();
        assertEquals(VerificationStatus.VERIFIED, je.status());
    }

    @Test
    void markFailedFromPendingTransitions() {
        JournalEntry je = balanced();
        je.markFailed();
        assertEquals(VerificationStatus.FAILED, je.status());
    }

    @Test
    void markVerifiedFromVerifiedRejected() {
        JournalEntry je = balanced();
        je.markVerified();
        IllegalJournalStatusTransitionException ex = assertThrows(
                IllegalJournalStatusTransitionException.class, je::markVerified);
        assertEquals(VerificationStatus.VERIFIED, ex.from());
        assertEquals(VerificationStatus.VERIFIED, ex.to());
    }

    @Test
    void markFailedAfterVerifiedRejected() {
        JournalEntry je = balanced();
        je.markVerified();
        assertThrows(IllegalJournalStatusTransitionException.class, je::markFailed);
        assertEquals(VerificationStatus.VERIFIED, je.status(), "status must not change on rejected transition");
    }

    @Test
    void movementsListIsUnmodifiable() {
        JournalEntry je = balanced();
        assertThrows(UnsupportedOperationException.class,
                () -> je.movements().add(new Movement(SOURCE, Money.of("1.00"), MovementType.DEBIT)));
    }

    @Test
    void modificationsToInputListAfterCreationDoNotAffectAggregate() {
        List<Movement> input = new ArrayList<>(List.of(
                new Movement(SOURCE, Money.of("10.00"), MovementType.DEBIT),
                new Movement(DEST, Money.of("10.00"), MovementType.CREDIT)));
        JournalEntry je = JournalEntry.create("defensive", NOW, input);
        input.clear();
        assertEquals(2, je.movements().size(), "aggregate must defensively copy the input list");
    }

    @Test
    void rehydrateBypassesValidation() {
        JournalEntry je = JournalEntry.rehydrate(
                JournalEntryId.generate(),
                "loaded",
                NOW,
                VerificationStatus.VERIFIED,
                List.of(new Movement(SOURCE, Money.of("10.00"), MovementType.DEBIT))); // 1 movement OK for rehydrate
        assertEquals(VerificationStatus.VERIFIED, je.status());
        assertEquals(1, je.movements().size());
    }

    private static JournalEntry balanced() {
        return JournalEntry.create("transfer", NOW, List.of(
                new Movement(SOURCE, Money.of("10.00"), MovementType.DEBIT),
                new Movement(DEST, Money.of("10.00"), MovementType.CREDIT)));
    }
}
