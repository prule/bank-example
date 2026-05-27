package com.bank.core.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class LedgerDomainTest {

    @Test
    public void testMovementConstructionSucceeds() {
        AccountId accountId = AccountId.generate();
        Money amount = Money.of("100.00");
        Movement movement = new Movement(accountId, amount, MovementType.CREDIT);

        assertEquals(accountId, movement.accountId());
        assertEquals(amount, movement.amount());
        assertEquals(MovementType.CREDIT, movement.type());
    }

    @Test
    public void testMovementNonPositiveAmountRejected() {
        AccountId accountId = AccountId.generate();
        assertThrows(InvalidAmountException.class, () -> new Movement(accountId, Money.ZERO, MovementType.CREDIT));
    }

    @Test
    public void testMovementNullChecks() {
        AccountId accountId = AccountId.generate();
        Money amount = Money.of("10.00");

        assertThrows(NullPointerException.class, () -> new Movement(null, amount, MovementType.CREDIT));
        assertThrows(NullPointerException.class, () -> new Movement(accountId, null, MovementType.CREDIT));
        assertThrows(NullPointerException.class, () -> new Movement(accountId, amount, null));
    }

    @Test
    public void testJournalEntryCreateSucceeds() {
        AccountId a1 = AccountId.generate();
        AccountId a2 = AccountId.generate();
        List<Movement> movements = List.of(
                new Movement(a1, Money.of("50.00"), MovementType.DEBIT),
                new Movement(a2, Money.of("50.00"), MovementType.CREDIT)
        );

        Instant now = Instant.now();
        JournalEntry journal = JournalEntry.create("Salary", now, movements);

        assertNotNull(journal.getId());
        assertEquals("Salary", journal.getDescription());
        assertEquals(now, journal.getTimestamp());
        assertEquals(VerificationStatus.PENDING, journal.getStatus());
        assertEquals(movements, journal.getMovements());
    }

    @Test
    public void testJournalEntryCreateRejectsUnbalanced() {
        AccountId a1 = AccountId.generate();
        AccountId a2 = AccountId.generate();
        List<Movement> movements = List.of(
                new Movement(a1, Money.of("50.00"), MovementType.DEBIT),
                new Movement(a2, Money.of("40.00"), MovementType.CREDIT)
        );

        UnbalancedJournalException ex = assertThrows(UnbalancedJournalException.class,
                () -> JournalEntry.create("Unbalanced", Instant.now(), movements));
        assertEquals(Money.of("40.00"), ex.getCreditSum());
        assertEquals(Money.of("50.00"), ex.getDebitSum());
    }

    @Test
    public void testJournalEntryCreateRejectsFewerThanTwoMovements() {
        AccountId a1 = AccountId.generate();
        assertThrows(IllegalArgumentException.class,
                () -> JournalEntry.create("Single", Instant.now(), List.of(new Movement(a1, Money.of("10.00"), MovementType.CREDIT))));
        assertThrows(IllegalArgumentException.class,
                () -> JournalEntry.create("Empty", Instant.now(), List.of()));
    }

    @Test
    public void testJournalEntryStatusTransitions() {
        AccountId a1 = AccountId.generate();
        AccountId a2 = AccountId.generate();
        List<Movement> movements = List.of(
                new Movement(a1, Money.of("50.00"), MovementType.DEBIT),
                new Movement(a2, Money.of("50.00"), MovementType.CREDIT)
        );

        // Verification success
        JournalEntry j1 = JournalEntry.create("Salary", Instant.now(), movements);
        j1.markVerified();
        assertEquals(VerificationStatus.VERIFIED, j1.getStatus());

        // Verified is terminal
        assertThrows(IllegalJournalStatusTransitionException.class, () -> j1.markVerified());
        assertThrows(IllegalJournalStatusTransitionException.class, () -> j1.markFailed());

        // Verification failure
        JournalEntry j2 = JournalEntry.create("Salary", Instant.now(), movements);
        j2.markFailed();
        assertEquals(VerificationStatus.FAILED, j2.getStatus());

        // Failed is terminal
        assertThrows(IllegalJournalStatusTransitionException.class, () -> j2.markVerified());
        assertThrows(IllegalJournalStatusTransitionException.class, () -> j2.markFailed());
    }
}
