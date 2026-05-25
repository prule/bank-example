package com.bank.core.application.audit;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectBalanceDriftTest {

    private static final AccountNumber CLEARING_NUM = AccountNumber.of("CLEARING-000");

    private LedgerMovements movements;
    private Accounts accounts;
    private AuditCheckpoints checkpoints;
    private DetectBalanceDrift useCase;

    @BeforeEach
    void setUp() {
        movements = mock(LedgerMovements.class);
        accounts = mock(Accounts.class);
        checkpoints = mock(AuditCheckpoints.class);
        useCase = new DetectBalanceDrift(movements, accounts, checkpoints, CLEARING_NUM);
    }

    private Account active(String number, String balance) {
        return Account.open(AccountNumber.of(number), Money.of(balance));
    }

    private Account suspended(String number, String balance) {
        Account a = active(number, balance);
        a.suspend();
        return a;
    }

    private Account clearing(String balance) {
        return Account.open(CLEARING_NUM, Money.of(balance));
    }

    private void stubSum(AccountId id, String signedAmount) {
        when(movements.sumSignedAmountForAccount(id)).thenReturn(new BigDecimal(signedAmount));
    }

    @Test
    void constructor_rejectsNullArgs() {
        assertEquals("movements cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new DetectBalanceDrift(null, accounts, checkpoints, CLEARING_NUM)).getMessage());
        assertEquals("accounts cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new DetectBalanceDrift(movements, null, checkpoints, CLEARING_NUM)).getMessage());
        assertEquals("checkpoints cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new DetectBalanceDrift(movements, accounts, null, CLEARING_NUM)).getMessage());
        assertEquals("clearingAccountNumber cannot be null",
                assertThrows(NullPointerException.class,
                        () -> new DetectBalanceDrift(movements, accounts, checkpoints, null)).getMessage());
    }

    @Test
    void auditNameConstantIsLiteralBalanceDrift() {
        assertEquals("balance_drift", DetectBalanceDrift.AUDIT_NAME,
                "the audit_checkpoint row name is part of the persistent contract — must not be renamed");
    }

    @Test
    void noNewMovements_isNoOp_butStillAdvancesCheckpoint() {
        when(checkpoints.readOrZero("balance_drift")).thenReturn(42L);
        when(movements.currentCeiling()).thenReturn(42L);

        DriftReport report = useCase.audit();

        verify(movements, never()).distinctAccountIdsInWindow(anyLong(), anyLong());
        verify(accounts, never()).findById(any());
        verify(accounts, never()).save(any());
        verify(checkpoints, times(1)).save("balance_drift", 42L);

        assertEquals(new DriftReport(42L, 42L, 0, 0), report);
    }

    @Test
    void emptyCandidateSet_advancesCheckpoint_noAccountLoads() {
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(100L);
        when(movements.distinctAccountIdsInWindow(0L, 100L)).thenReturn(Set.of());

        DriftReport report = useCase.audit();

        verify(accounts, never()).findById(any());
        verify(accounts, never()).save(any());
        verify(checkpoints, times(1)).save("balance_drift", 100L);
        assertEquals(new DriftReport(0L, 100L, 0, 0), report);
    }

    @Test
    void inBalanceCandidate_doesNotSuspend_advancesCheckpoint() {
        Account a = active("CUST-A", "100.00");
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        stubSum(a.id(), "100.00");

        DriftReport report = useCase.audit();

        verify(accounts, never()).save(any());
        verify(checkpoints, times(1)).save("balance_drift", 50L);
        assertEquals(new DriftReport(0L, 50L, 1, 0), report);
    }

    @Test
    void driftedActiveAccount_isSuspended_andLogged() {
        Account a = active("CUST-D", "100.00");
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        stubSum(a.id(), "90.00");

        DriftReport report = useCase.audit();

        verify(accounts, times(1)).save(a);
        assertEquals(com.bank.core.domain.AccountStatus.SUSPENDED, a.status());
        verify(checkpoints, times(1)).save("balance_drift", 50L);
        assertEquals(new DriftReport(0L, 50L, 1, 1), report);
        // ERROR log line is verified in the integration test, not here — Logback is not
        // on the application module's test classpath (kept Spring-/framework-free per F00).
    }

    @Test
    void driftedSuspendedAccount_isNotResaved() {
        Account a = suspended("CUST-S", "100.00");
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));

        DriftReport report = useCase.audit();

        verify(movements, never()).sumSignedAmountForAccount(a.id()); // status check short-circuits
        verify(accounts, never()).save(any());
        assertEquals(new DriftReport(0L, 50L, 1, 0), report);
    }

    @Test
    void driftedClosedAccount_isSkippedExactlyLikeSuspended() {
        // Account has no public close() mutator — CLOSED state only enters via
        // rehydration from persisted rows. Use the rehydrate factory directly.
        Account a = com.bank.core.domain.Account.rehydrate(
                com.bank.core.domain.AccountId.generate(),
                AccountNumber.of("CUST-C"),
                Money.of("100.00"),
                com.bank.core.domain.AccountStatus.CLOSED);
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));

        DriftReport report = useCase.audit();

        verify(movements, never()).sumSignedAmountForAccount(a.id());
        verify(accounts, never()).save(any());
        assertEquals(new DriftReport(0L, 50L, 1, 0), report);
    }

    @Test
    void clearingAccountInCandidateSet_isCarveOut_doesNotCountTowardInspected() {
        Account drifted = active("CUST-X", "100.00");
        Account clearing = clearing("500.00"); // cached 500
        Set<AccountId> ordered = new LinkedHashSet<>();
        ordered.add(drifted.id());
        ordered.add(clearing.id());

        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(100L);
        when(movements.distinctAccountIdsInWindow(0L, 100L)).thenReturn(ordered);
        when(accounts.findById(drifted.id())).thenReturn(Optional.of(drifted));
        when(accounts.findById(clearing.id())).thenReturn(Optional.of(clearing));
        stubSum(drifted.id(), "50.00");
        // Note: sumSignedAmountForAccount is never called for the clearing account
        // because the carve-out skip happens before the sum query.

        DriftReport report = useCase.audit();

        verify(accounts, times(1)).save(drifted);
        verify(accounts, never()).save(clearing);
        verify(movements, never()).sumSignedAmountForAccount(clearing.id());
        assertEquals(new DriftReport(0L, 100L, 1, 1), report,
                "clearing account is NOT counted in inspected (carve-out happens first)");
        // INFO carve-out log line is verified in the integration test.
    }

    @Test
    void missingAccountInCandidateSet_isSilentlySkipped() {
        AccountId ghostId = AccountId.generate();
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(ghostId));
        when(accounts.findById(ghostId)).thenReturn(Optional.empty());

        DriftReport report = useCase.audit();

        verify(movements, never()).sumSignedAmountForAccount(ghostId);
        verify(accounts, never()).save(any());
        assertEquals(new DriftReport(0L, 50L, 0, 0), report,
                "missing account does NOT count toward inspected");
    }

    @Test
    void negativeRawSum_isClampedToZeroForComparison() {
        Account a = active("CUST-Z", "0.00");
        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(50L);
        when(movements.distinctAccountIdsInWindow(0L, 50L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        stubSum(a.id(), "-5.00");

        DriftReport report = useCase.audit();

        // Quirk documented in design: negative raw sum clamped to zero; if cached is also zero,
        // no drift is detected even though the ledger is corrupted.
        verify(accounts, never()).save(any());
        assertEquals(new DriftReport(0L, 50L, 1, 0), report);
    }

    @Test
    void iterationOrder_matchesCandidateSetOrder() {
        Account a = active("CUST-1", "10.00");
        Account b = active("CUST-2", "20.00");
        Account c = active("CUST-3", "30.00");
        LinkedHashSet<AccountId> ordered = new LinkedHashSet<>();
        ordered.add(a.id());
        ordered.add(b.id());
        ordered.add(c.id());

        when(checkpoints.readOrZero("balance_drift")).thenReturn(0L);
        when(movements.currentCeiling()).thenReturn(60L);
        when(movements.distinctAccountIdsInWindow(0L, 60L)).thenReturn(ordered);
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        when(accounts.findById(b.id())).thenReturn(Optional.of(b));
        when(accounts.findById(c.id())).thenReturn(Optional.of(c));
        stubSum(a.id(), "10.00");
        stubSum(b.id(), "20.00");
        stubSum(c.id(), "30.00");

        useCase.audit();

        InOrder seq = inOrder(accounts);
        seq.verify(accounts).findById(a.id());
        seq.verify(accounts).findById(b.id());
        seq.verify(accounts).findById(c.id());
    }

    @Test
    void checkpointSave_alwaysHappensOnHappyPath() {
        // No-op path
        when(checkpoints.readOrZero("balance_drift")).thenReturn(10L);
        when(movements.currentCeiling()).thenReturn(10L);
        useCase.audit();
        verify(checkpoints, times(1)).save(eq("balance_drift"), eq(10L));

        // Empty candidate path
        when(checkpoints.readOrZero("balance_drift")).thenReturn(10L);
        when(movements.currentCeiling()).thenReturn(20L);
        when(movements.distinctAccountIdsInWindow(10L, 20L)).thenReturn(Set.of());
        useCase.audit();
        verify(checkpoints, times(1)).save(eq("balance_drift"), eq(20L));

        // In-balance path
        Account a = active("CUST-OK", "5.00");
        when(checkpoints.readOrZero("balance_drift")).thenReturn(20L);
        when(movements.currentCeiling()).thenReturn(30L);
        when(movements.distinctAccountIdsInWindow(20L, 30L)).thenReturn(Set.of(a.id()));
        when(accounts.findById(a.id())).thenReturn(Optional.of(a));
        stubSum(a.id(), "5.00");
        useCase.audit();
        verify(checkpoints, times(1)).save(eq("balance_drift"), eq(30L));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
