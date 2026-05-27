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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Spring-free application use case that coordinates balance transfers between accounts
 * under pessimistic lock-then-load contention management.
 */
public final class TransferFunds {
    private final Accounts accounts;
    private final JournalEntries journalEntries;
    private final AccountLocker locker;
    private final Clock clock;

    public TransferFunds(Accounts accounts, JournalEntries journalEntries, AccountLocker locker, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "Accounts port must not be null");
        this.journalEntries = Objects.requireNonNull(journalEntries, "JournalEntries port must not be null");
        this.locker = Objects.requireNonNull(locker, "AccountLocker must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Executes an atomic transfer of funds from one account to another.
     *
     * @param command the transfer command containing source, destination, and amount
     * @throws SameAccountTransferException if source and destination are identical
     * @throws ResourceNotFoundException    if either account does not exist
     */
    public void transfer(TransferCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // Cheap self-transfer check before lock acquisition to prevent locking map overhead
        if (command.sourceAccountNumber().equals(command.destinationAccountNumber())) {
            throw new SameAccountTransferException(command.sourceAccountNumber());
        }

        // Acquire paired locks in original caller order; locker canonicalises ordering to prevent deadlocks
        locker.withPairedLocks(command.sourceAccountNumber(), command.destinationAccountNumber(), () -> {
            // Load aggregates inside the locked context (lock-then-load) to prevent stale reads
            Account source = accounts.findByNumber(command.sourceAccountNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("account", command.sourceAccountNumber()));
            Account destination = accounts.findByNumber(command.destinationAccountNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("account", command.destinationAccountNumber()));

            // Domain aggregate state mutations and validation
            source.debit(command.amount());
            destination.credit(command.amount());

            // Build double-entry balanced movements
            Movement debitMovement = new Movement(source.getId(), command.amount(), MovementType.DEBIT);
            Movement creditMovement = new Movement(destination.getId(), command.amount(), MovementType.CREDIT);

            String description = String.format("Transfer from %s to %s",
                    command.sourceAccountNumber(), command.destinationAccountNumber());
            Instant timestamp = clock.instant();

            // Creates a PENDING, balanced double-entry JournalEntry
            JournalEntry journalEntry = JournalEntry.create(description, timestamp, List.of(debitMovement, creditMovement));

            // Persist the modified aggregates and double-entry ledger entry
            accounts.save(source);
            accounts.save(destination);
            journalEntries.save(journalEntry);
        });
    }
}
