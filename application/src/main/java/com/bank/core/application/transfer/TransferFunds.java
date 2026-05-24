package com.bank.core.application.transfer;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.concurrency.AccountLocker;
import com.bank.core.application.ledger.JournalEntries;
import com.bank.core.domain.Account;
import com.bank.core.domain.JournalEntry;
import com.bank.core.domain.Movement;
import com.bank.core.domain.MovementType;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the fund-transfer use case. Composes:
 * <ul>
 *   <li>F05's {@link Accounts} port for aggregate load/save,</li>
 *   <li>F02's {@link JournalEntries} port for the balanced journal entry,</li>
 *   <li>F07's {@link AccountLocker} for paired-lock concurrency.</li>
 * </ul>
 *
 * <h2>Lock-then-load</h2>
 * The use case acquires paired locks <em>before</em> loading aggregates. A
 * load-before-lock variant would race: another concurrent transfer between
 * the same pair could mutate the row between our read and our lock, leaving
 * us holding a stale in-memory aggregate that would overwrite the
 * concurrent change on save. Lock-then-load guarantees the aggregate we
 * mutate is the same row no one else can touch until we release.
 *
 * <h2>Transactional boundary</h2>
 * This class does not own its transaction. Per F02's
 * {@code transactional-in-application} precedent the {@code application}
 * module is Spring-free; the {@code @Transactional} sits on
 * {@code com.bank.core.infrastructure.web.transfer.TransferController.createTransfer}.
 * That boundary wraps the entire pipeline so all four writes (source
 * UPDATE, destination UPDATE, journal_entry INSERT, two ledger_movement
 * INSERTs) share a single JDBC connection and either commit or roll back
 * together. F07's lock release runs on {@code afterCompletion}, so locks
 * free up on both commit and rollback.
 */
public final class TransferFunds {

    private final Accounts accounts;
    private final JournalEntries journals;
    private final AccountLocker locker;
    private final Clock clock;

    public TransferFunds(Accounts accounts,
                         JournalEntries journals,
                         AccountLocker locker,
                         Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.journals = Objects.requireNonNull(journals, "journals");
        this.locker = Objects.requireNonNull(locker, "locker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void transfer(TransferCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        // Cheap pre-check: no point taking a lock to reject a self-transfer.
        if (command.source().equals(command.destination())) {
            throw new SameAccountTransferException(command.source());
        }

        // Caller-supplied argument order is passed through; the locker
        // canonicalises by min(value) so concurrent (A,B) and (B,A) callers
        // contend on the same first lock per F07's spec.
        locker.withPairedLocks(command.source(), command.destination(), () -> {
            Account source = accounts.findByNumber(command.source())
                    .orElseThrow(() -> new ResourceNotFoundException("account", command.source().value()));
            Account destination = accounts.findByNumber(command.destination())
                    .orElseThrow(() -> new ResourceNotFoundException("account", command.destination().value()));

            // Domain mutators enforce ACTIVE-status and sufficient-funds
            // invariants; thrown DomainExceptions propagate out, rolling
            // back the surrounding transaction and releasing the locks.
            source.debit(command.amount());
            destination.credit(command.amount());

            accounts.save(source);
            accounts.save(destination);

            JournalEntry entry = JournalEntry.create(
                    "Transfer from " + command.source() + " to " + command.destination(),
                    clock.instant(),
                    List.of(
                            new Movement(source.id(), command.amount(), MovementType.DEBIT),
                            new Movement(destination.id(), command.amount(), MovementType.CREDIT)
                    )
            );
            journals.save(entry);
        });
    }
}
