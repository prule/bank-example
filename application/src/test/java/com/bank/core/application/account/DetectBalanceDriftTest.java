package com.bank.core.application.account;

import com.bank.core.application.ledger.AuditCheckpoints;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class DetectBalanceDriftTest {

    private FakeAuditCheckpoints checkpoints;
    private FakeJournalEntries journalEntries;
    private FakeAccounts accounts;
    private DetectBalanceDrift useCase;

    private static final String CLEARING_NUM = "CLEARING-000";

    @BeforeEach
    public void setUp() {
        checkpoints = new FakeAuditCheckpoints();
        journalEntries = new FakeJournalEntries();
        accounts = new FakeAccounts();
        useCase = new DetectBalanceDrift(checkpoints, journalEntries, accounts);
    }

    @Test
    public void testNoNewMovementsNoOp() {
        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 100L);
        journalEntries.setCeiling(100L); // ceiling <= floor

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(100L, report.floor());
        assertEquals(100L, report.ceiling());
        assertEquals(0, report.inspected());
        assertEquals(0, report.drifted());
        assertEquals(100L, checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME));
    }

    @Test
    public void testActiveAccountInBalanceInspected() {
        Account account = Account.open("ACC-001", Money.of("100.00"));
        accounts.save(account);

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, account.getId());
        journalEntries.setSignedSum(account.getId(), new BigDecimal("100.00"));

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(10L, report.floor());
        assertEquals(20L, report.ceiling());
        assertEquals(1, report.inspected());
        assertEquals(0, report.drifted());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertEquals(20L, checkpoints.readOrZero(DetectBalanceDrift.AUDIT_NAME));
    }

    @Test
    public void testActiveAccountDriftSuspendsAndLogs() {
        Account account = Account.open("ACC-001", Money.of("100.00"));
        accounts.save(account);

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, account.getId());
        journalEntries.setSignedSum(account.getId(), new BigDecimal("90.00")); // Drift!

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(1, report.inspected());
        assertEquals(1, report.drifted());
        assertEquals(AccountStatus.SUSPENDED, account.getStatus());
        assertTrue(accounts.isSaved(account.getId()));
    }

    @Test
    public void testClearingAccountCarveOutSkipped() {
        Account clearing = Account.open(CLEARING_NUM, Money.of("100.00"));
        accounts.save(clearing);

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, clearing.getId());
        journalEntries.setSignedSum(clearing.getId(), new BigDecimal("90.00")); // Would drift

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(0, report.inspected()); // skipped before inspected++
        assertEquals(0, report.drifted());
        assertEquals(AccountStatus.ACTIVE, clearing.getStatus()); // remains ACTIVE
    }

    @Test
    public void testClosedOrSuspendedAccountSkippedButInspected() {
        Account suspended = Account.open("ACC-001", Money.of("100.00"));
        suspended.suspend();
        accounts.save(suspended);

        Account closed = Account.open("ACC-002", Money.of("100.00"));
        closed.close();
        accounts.save(closed);

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, suspended.getId());
        journalEntries.addCandidate(20L, closed.getId());
        journalEntries.setSignedSum(suspended.getId(), new BigDecimal("90.00")); // Would drift
        journalEntries.setSignedSum(closed.getId(), new BigDecimal("90.00")); // Would drift

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(2, report.inspected()); // inspected increments
        assertEquals(0, report.drifted()); // no suspension triggered
        assertEquals(AccountStatus.SUSPENDED, suspended.getStatus());
        assertEquals(AccountStatus.CLOSED, closed.getStatus());
    }

    @Test
    public void testDefensiveMissingAccountSkipped() {
        AccountId missingId = AccountId.generate();

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, missingId);

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(0, report.inspected()); // skipped completely
        assertEquals(0, report.drifted());
    }

    @Test
    public void testRecomputeDefensiveNegativeBound() {
        Account account = Account.open("ACC-001", Money.of("0.00"));
        accounts.save(account);

        checkpoints.save(DetectBalanceDrift.AUDIT_NAME, 10L);
        journalEntries.setCeiling(20L);
        journalEntries.addCandidate(20L, account.getId());
        journalEntries.setSignedSum(account.getId(), new BigDecimal("-50.00")); // Negative sum! expected max(0, -50) = 0.00

        DriftReport report = useCase.audit(CLEARING_NUM);

        assertEquals(1, report.inspected());
        assertEquals(0, report.drifted()); // expected balance of 0.00 matches cached balance of 0.00, so NO drift!
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    // --- Mockito-free lightweight fakes ---

    private static class FakeAuditCheckpoints implements AuditCheckpoints {
        private final Map<String, Long> checkpoints = new HashMap<>();

        @Override
        public long readOrZero(String auditName) {
            return checkpoints.getOrDefault(auditName, 0L);
        }

        @Override
        public void save(String auditName, long lastMovementId) {
            checkpoints.put(auditName, lastMovementId);
        }
    }

    private static class FakeJournalEntries implements JournalEntries {
        private long ceiling = 0;
        private final Map<Long, List<AccountId>> candidatesMap = new HashMap<>();
        private final Map<AccountId, BigDecimal> sumsMap = new HashMap<>();

        public void setCeiling(long ceiling) {
            this.ceiling = ceiling;
        }

        public void addCandidate(long atCeiling, AccountId accountId) {
            candidatesMap.computeIfAbsent(atCeiling, k -> new ArrayList<>()).add(accountId);
        }

        public void setSignedSum(AccountId accountId, BigDecimal sum) {
            sumsMap.put(accountId, sum);
        }

        @Override
        public void save(JournalEntry journalEntry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<JournalEntry> findById(JournalEntryId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<JournalEntry> findByStatus(VerificationStatus status, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isBalanced(JournalEntryId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long currentCeiling() {
            return ceiling;
        }

        @Override
        public List<AccountId> distinctAccountIdsInWindow(long floor, long ceiling) {
            List<AccountId> results = new ArrayList<>();
            for (long i = floor + 1; i <= ceiling; i++) {
                List<AccountId> ids = candidatesMap.get(i);
                if (ids != null) {
                    results.addAll(ids);
                }
            }
            // deduplicate
            return results.stream().distinct().toList();
        }

        @Override
        public BigDecimal sumSignedAmountForAccount(AccountId id) {
            return sumsMap.getOrDefault(id, BigDecimal.ZERO);
        }
    }

    private static class FakeAccounts implements Accounts {
        private final Map<AccountId, Account> byId = new HashMap<>();
        private final Set<AccountId> savedIds = new HashSet<>();

        @Override
        public Optional<Account> findByNumber(String number) {
            return byId.values().stream().filter(a -> a.getNumber().equals(number)).findFirst();
        }

        @Override
        public Optional<Account> findById(AccountId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Account save(Account account) {
            byId.put(account.getId(), account);
            savedIds.add(account.getId());
            return account;
        }

        public boolean isSaved(AccountId id) {
            return savedIds.contains(id);
        }
    }
}
