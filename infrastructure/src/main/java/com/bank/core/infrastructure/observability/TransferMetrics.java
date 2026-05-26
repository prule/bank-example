package com.bank.core.infrastructure.observability;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.domain.LockAcquisitionTimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Infrastructure-boundary wrapper around {@link TransferFunds} that records
 * Micrometer metrics for every transfer attempt. Lives here, not in the
 * application module, because {@code application/} is intentionally framework-
 * free (ArchUnit boundary rule {@code applicationHasNoFrameworkDependencies}
 * forbids Spring + JPA + dto + api; this change adds Micrometer to that list
 * by a sibling rule).
 *
 * <h2>What it records</h2>
 * <ul>
 *   <li>{@code bank.transfer.duration} — a {@link Timer} sampled around the
 *       use-case call. Recorded on every outcome including unexpected exceptions.</li>
 *   <li>{@code bank.transfer.executed} — a {@link Counter} tagged
 *       {@code outcome} with one of {@code success}, {@code insufficient_funds},
 *       {@code account_suspended}, {@code lock_timeout}. Other exceptions
 *       (e.g. {@code SameAccountTransferException}, {@code ResourceNotFoundException})
 *       are intentionally NOT counted — the spec restricts this metric to the
 *       four classified outcomes to keep tag cardinality bounded by a small
 *       enum-like set.</li>
 * </ul>
 *
 * <h2>Re-throw discipline</h2>
 * Every caught exception is re-thrown unchanged so the surrounding
 * {@code @Transactional} boundary on {@code TransferController.createTransfer}
 * rolls back and {@code GlobalExceptionHandler} produces the correct error
 * envelope. This class never swallows.
 *
 * <h2>Counter cache</h2>
 * The four outcome counters are constructed once in the constructor and held
 * as fields. Pre-registering at startup beats calling {@code registry.counter(...)}
 * on every transfer (Micrometer's name-resolution path is cheap but non-zero)
 * and means the per-outcome series exist in {@code /actuator/prometheus} from
 * the first scrape regardless of whether any traffic has hit them yet.
 */
@Component
public class TransferMetrics {

    private static final String TIMER_NAME = "bank.transfer.duration";
    private static final String COUNTER_NAME = "bank.transfer.executed";

    private final TransferFunds delegate;
    private final Timer timer;
    private final Counter successCounter;
    private final Counter insufficientFundsCounter;
    private final Counter accountSuspendedCounter;
    private final Counter lockTimeoutCounter;

    public TransferMetrics(TransferFunds delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(registry, "registry");
        this.timer = Timer.builder(TIMER_NAME)
                .description("Wall-clock duration of a transfer attempt, regardless of outcome.")
                .register(registry);
        this.successCounter = outcomeCounter(registry, "success");
        this.insufficientFundsCounter = outcomeCounter(registry, "insufficient_funds");
        this.accountSuspendedCounter = outcomeCounter(registry, "account_suspended");
        this.lockTimeoutCounter = outcomeCounter(registry, "lock_timeout");
    }

    public void transfer(TransferCommand command) {
        Timer.Sample sample = Timer.start();
        try {
            delegate.transfer(command);
            successCounter.increment();
        } catch (InsufficientFundsException ex) {
            insufficientFundsCounter.increment();
            throw ex;
        } catch (AccountInactiveException ex) {
            accountSuspendedCounter.increment();
            throw ex;
        } catch (LockAcquisitionTimeoutException ex) {
            lockTimeoutCounter.increment();
            throw ex;
        } finally {
            // Timer fires on every attempt: success, classified rejection, or
            // unclassified exception. The spec's success/insufficient_funds/
            // account_suspended/lock_timeout breakdown applies only to the
            // counter; the timer is outcome-agnostic.
            sample.stop(timer);
        }
    }

    private static Counter outcomeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(COUNTER_NAME)
                .description("Count of transfer attempts, tagged by outcome.")
                .tag("outcome", outcome)
                .register(registry);
    }
}
