package com.bank.core.application.account;

import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAccountTest {

    private FakeAccounts accounts;
    private FakeJournalEntries journalEntries;
    private FakeAccountLocker locker;
    private Clock clock;
    private TransferFunds transferFunds;
    private OpenAccount openAccount;

    private static final String CLEARING_NUM = "CLEARING-000";
    private static final String CUST_NUM = "ACC-123";

    @BeforeEach
    public void setUp() {
        accounts = new FakeAccounts();
        journalEntries = new FakeJournalEntries();
        locker = new FakeAccountLocker();
        clock = Clock.systemUTC();
        transferFunds = new TransferFunds(accounts, journalEntries, locker, clock);
        openAccount = new OpenAccount(accounts, transferFunds, CLEARING_NUM);
    }

    @Test
    public void testOpenAccountCommandValidation() {
        // Assert null checks
        assertThrows(NullPointerException.class, () -> new OpenAccountCommand(null, Money.of("10.00")));
        assertThrows(NullPointerException.class, () -> new OpenAccountCommand(CUST_NUM, null));

        // Assert empty/whitespace checks
        assertThrows(IllegalArgumentException.class, () -> new OpenAccountCommand("", Money.of("10.00")));
        assertThrows(IllegalArgumentException.class, () -> new OpenAccountCommand("   ", Money.of("10.00")));
    }

    @Test
    public void testOpenAccountConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new OpenAccount(null, transferFunds, CLEARING_NUM));
        assertThrows(NullPointerException.class, () -> new OpenAccount(accounts, null, CLEARING_NUM));
        assertThrows(NullPointerException.class, () -> new OpenAccount(accounts, transferFunds, null));
    }

    @Test
    public void testEarlyDuplicateAccountCheck() {
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.ZERO);

        // Pre-populate duplicate account in the fake repo
        Account existingAccount = Account.open(CUST_NUM, Money.ZERO);
        accounts.save(existingAccount);

        DuplicateAccountNumberException ex = assertThrows(DuplicateAccountNumberException.class, () -> {
            openAccount.open(command);
        });

        assertEquals(CUST_NUM, ex.number());
        // Verify duplicate account was not modified or overwritten (balance remains unchanged)
        Optional<Account> retrieved = accounts.findByNumber(CUST_NUM);
        assertTrue(retrieved.isPresent());
        assertEquals(Money.ZERO, retrieved.get().getBalance());
        assertEquals(0, journalEntries.getEntries().size());
    }

    @Test
    public void testEarlyClearingAccountMissingForPositiveOpen() {
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.of("100.00"));

        // Ensure clearing account is missing from the fake repo
        // And customer account does not exist either
        ClearingAccountMissingException ex = assertThrows(ClearingAccountMissingException.class, () -> {
            openAccount.open(command);
        });

        assertEquals(CLEARING_NUM, ex.clearingAccountNumber());
        assertFalse(accounts.findByNumber(CUST_NUM).isPresent());
        assertEquals(0, journalEntries.getEntries().size());
    }

    @Test
    public void testZeroBalanceOpenSuccess() {
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, Money.ZERO);

        Account result = openAccount.open(command);

        assertNotNull(result);
        assertEquals(CUST_NUM, result.getNumber());
        assertEquals(Money.ZERO, result.getBalance());
        assertEquals(AccountStatus.ACTIVE, result.getStatus());

        // Verify it was correctly saved to the fake repo
        Optional<Account> saved = accounts.findByNumber(CUST_NUM);
        assertTrue(saved.isPresent());
        assertEquals(Money.ZERO, saved.get().getBalance());
        assertEquals(AccountStatus.ACTIVE, saved.get().getStatus());

        // Verify zero open never triggered any transfer or double-entry journal movement
        assertEquals(0, journalEntries.getEntries().size());
    }

    @Test
    public void testPositiveBalanceOpenSuccess() {
        Money openingBalance = Money.of("250.00");
        OpenAccountCommand command = new OpenAccountCommand(CUST_NUM, openingBalance);

        // Populate clearing account with sufficient funds in the fake repo
        Account clearingAccount = Account.open(CLEARING_NUM, Money.of("1000.00"));
        accounts.save(clearingAccount);

        Account result = openAccount.open(command);

        assertNotNull(result);
        assertEquals(CUST_NUM, result.getNumber());
        assertEquals(openingBalance, result.getBalance());

        // Verify the customer account is stored and has the funded balance
        Optional<Account> savedCust = accounts.findByNumber(CUST_NUM);
        assertTrue(savedCust.isPresent());
        assertEquals(openingBalance, savedCust.get().getBalance());

        // Verify the clearing account was debited
        Optional<Account> savedClearing = accounts.findByNumber(CLEARING_NUM);
        assertTrue(savedClearing.isPresent());
        assertEquals(Money.of("750.00"), savedClearing.get().getBalance());

        // Verify a double-entry ledger journal entry was saved
        List<JournalEntry> entries = journalEntries.getEntries();
        assertEquals(1, entries.size());
        JournalEntry entry = entries.get(0);
        assertTrue(entry.getDescription().contains("Transfer from " + CLEARING_NUM + " to " + CUST_NUM));
        assertEquals(2, entry.getMovements().size());

        Movement m1 = entry.getMovements().get(0);
        assertEquals(clearingAccount.getId(), m1.accountId());
        assertEquals(openingBalance, m1.amount());
        assertEquals(MovementType.DEBIT, m1.type());

        Movement m2 = entry.getMovements().get(1);
        assertEquals(savedCust.get().getId(), m2.accountId());
        assertEquals(openingBalance, m2.amount());
        assertEquals(MovementType.CREDIT, m2.type());
    }

    // --- Fake implementations to bypass JDK 25 Mockito inline mockmaker limitations ---

    private static class FakeAccounts implements Accounts {
        private final Map<String, Account> byNumber = new HashMap<>();
        private final Map<AccountId, Account> byId = new HashMap<>();

        @Override
        public Optional<Account> findByNumber(String number) {
            return Optional.ofNullable(byNumber.get(number));
        }

        @Override
        public Optional<Account> findById(AccountId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Account save(Account account) {
            byNumber.put(account.getNumber(), account);
            byId.put(account.getId(), account);
            return account;
        }
    }

    private static class FakeJournalEntries implements JournalEntries {
        private final List<JournalEntry> entries = new ArrayList<>();

        public List<JournalEntry> getEntries() {
            return entries;
        }

        @Override
        public void save(JournalEntry journalEntry) {
            entries.add(journalEntry);
        }

        @Override
        public Optional<JournalEntry> findById(JournalEntryId id) {
            return entries.stream().filter(e -> e.getId().equals(id)).findFirst();
        }

        @Override
        public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
            return entries.stream().filter(e -> e.getStatus() == status).limit(limit).toList();
        }

        @Override
        public boolean isBalanced(JournalEntryId id) {
            return true;
        }

        @Override
        public long currentCeiling() {
            return 0;
        }

        @Override
        public List<AccountId> distinctAccountIdsInWindow(long floor, long ceiling) {
            return Collections.emptyList();
        }

        @Override
        public java.math.BigDecimal sumSignedAmountForAccount(AccountId id) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private static class FakeAccountLocker implements AccountLocker {
        @Override
        public void withPairedLocks(String a, String b, Runnable action) {
            action.run();
        }

        @Override
        public long getWaitMs() {
            return 0;
        }
    }
}
