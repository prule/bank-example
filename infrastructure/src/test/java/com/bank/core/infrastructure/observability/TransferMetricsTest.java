package com.bank.core.infrastructure.observability;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.AccountStatus;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import com.bank.core.domain.Money;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link TransferMetrics}. Uses {@link SimpleMeterRegistry}
 * and a Mockito-mocked {@link TransferFunds}; no Spring context, no DB.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Per-outcome counter increments for each classified exception type
 *       and the no-exception success case.</li>
 *   <li>Exceptions are re-thrown unchanged (same reference).</li>
 *   <li>{@code bank.transfer.duration} sample count increases on every
 *       invocation regardless of outcome (including unclassified exceptions).</li>
 *   <li>Unclassified exceptions do NOT increment any
 *       {@code bank_transfer_executed_total} series.</li>
 * </ul>
 */
class TransferMetricsTest {

    private static final TransferCommand CMD =
            new TransferCommand(AccountNumber.of("ACC-1"), AccountNumber.of("ACC-2"), Money.of("10.00"));

    private SimpleMeterRegistry registry;
    private TransferFunds delegate;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        delegate = mock(TransferFunds.class);
    }

    @Test
    void successIncrementsSuccessCounterAndTimer() {
        doNothing().when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        metrics.transfer(CMD);

        assertEquals(1.0, outcomeCount("success"));
        assertEquals(0.0, outcomeCount("insufficient_funds"));
        assertEquals(0.0, outcomeCount("account_suspended"));
        assertEquals(0.0, outcomeCount("lock_timeout"));
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void insufficientFundsIncrementsInsufficientFundsCounterAndRethrows() {
        InsufficientFundsException expected = new InsufficientFundsException(
                AccountId.generate(), Money.of("100.00"), Money.of("1.00"));
        doThrow(expected).when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        InsufficientFundsException thrown = assertThrows(
                InsufficientFundsException.class, () -> metrics.transfer(CMD));

        assertSame(expected, thrown);
        assertEquals(1.0, outcomeCount("insufficient_funds"));
        assertEquals(0.0, outcomeCount("success"));
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void accountInactiveIncrementsAccountSuspendedCounterAndRethrows() {
        AccountInactiveException expected = new AccountInactiveException(
                AccountId.generate(), AccountStatus.SUSPENDED);
        doThrow(expected).when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        AccountInactiveException thrown = assertThrows(
                AccountInactiveException.class, () -> metrics.transfer(CMD));

        assertSame(expected, thrown);
        assertEquals(1.0, outcomeCount("account_suspended"));
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void lockTimeoutIncrementsLockTimeoutCounterAndRethrows() {
        LockAcquisitionTimeoutException expected = new LockAcquisitionTimeoutException(
                AccountNumber.of("ACC-1"), AccountNumber.of("ACC-2"), 5000L);
        doThrow(expected).when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        LockAcquisitionTimeoutException thrown = assertThrows(
                LockAcquisitionTimeoutException.class, () -> metrics.transfer(CMD));

        assertSame(expected, thrown);
        assertEquals(1.0, outcomeCount("lock_timeout"));
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void sameAccountExceptionIsRethrownWithoutTouchingOutcomeCounters() {
        SameAccountTransferException self = new SameAccountTransferException(AccountNumber.of("ACC-1"));
        doThrow(self).when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        assertThrows(SameAccountTransferException.class, () -> metrics.transfer(CMD));

        assertEquals(0.0, outcomeCount("success"));
        assertEquals(0.0, outcomeCount("insufficient_funds"));
        assertEquals(0.0, outcomeCount("account_suspended"));
        assertEquals(0.0, outcomeCount("lock_timeout"));
        // Timer still records — the spec allows this for unclassified outcomes.
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void resourceNotFoundExceptionIsRethrownWithoutTouchingOutcomeCounters() {
        ResourceNotFoundException nf = new ResourceNotFoundException("account", "ACC-X");
        doThrow(nf).when(delegate).transfer(CMD);
        TransferMetrics metrics = new TransferMetrics(delegate, registry);

        assertThrows(ResourceNotFoundException.class, () -> metrics.transfer(CMD));

        assertEquals(0.0, outcomeCount("success"));
        assertEquals(1, registry.timer("bank.transfer.duration").count());
    }

    @Test
    void countersAreEagerlyRegisteredBeforeAnyTraffic() {
        new TransferMetrics(delegate, registry);

        for (String outcome : new String[] {"success", "insufficient_funds", "account_suspended", "lock_timeout"}) {
            Counter c = registry.find("bank.transfer.executed").tag("outcome", outcome).counter();
            // counter() returns null if no meter matches; we want it present at zero.
            assertEquals(0.0, c == null ? Double.NaN : c.count(),
                    "expected pre-registered counter for outcome=" + outcome);
        }
    }

    private double outcomeCount(String outcome) {
        Counter c = registry.find("bank.transfer.executed").tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }
}
