package com.bank.core.application.account;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.ClearingAccountMissingException;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.Money;

import java.util.Objects;

/**
 * Orchestrates the F08 account-opening use case. Creates a new Active account
 * at balance zero and, for a positive opening balance, funds it from the
 * configured clearing account via exactly one F06 transfer so every cent ever
 * credited to the new account has a matching ledger movement.
 *
 * <h2>Transactional boundary</h2>
 * This class does not own its transaction. Per F02's
 * {@code transactional-in-application} precedent the {@code application}
 * module is Spring-free; the {@code @Transactional} sits on
 * {@code com.bank.core.infrastructure.account.OpenAccountService}. That
 * boundary wraps the entire pipeline so every write produced by an
 * {@code open(...)} call (one {@code account} INSERT for the new account
 * plus — for a positive open — two {@code account} UPDATEs, one
 * {@code journal_entry} INSERT, two {@code ledger_movement} INSERTs) commits
 * together or rolls back together. F07's lock release (inside the F06 call)
 * runs on {@code afterCompletion}, so locks free up on both outcomes.
 *
 * <h2>Lock-then-load</h2>
 * F08 itself does not interact with the locker. The F06 transfer this class
 * invokes handles the lock-then-load contract internally for the
 * clearing-account / new-account pair.
 *
 * <h2>Duplicate detection</h2>
 * The pre-check via {@link Accounts#findByNumber(AccountNumber)} produces a
 * deterministic {@link DuplicateAccountNumberException} for the single-caller
 * path. The F05 unique index on {@code account.account_number} is the real
 * concurrent-write safety net; a race that defeats the pre-check still fails
 * the INSERT and rolls the transaction back inside the same boundary.
 *
 * <h2>Why reload after funding</h2>
 * After the F06 transfer commits the credit, the in-memory {@code newAccount}
 * aggregate this class just saved is stale by one credit (F06 loaded and
 * mutated its own copy through the {@link Accounts} port). The post-funding
 * {@link Accounts#findByNumber(AccountNumber)} reload returns the funded
 * state to callers without coupling F08 to F06's mutation order.
 */
public final class OpenAccount {

    private final Accounts accounts;
    private final TransferFunds transferFunds;
    private final AccountNumber clearingAccountNumber;

    public OpenAccount(Accounts accounts,
                       TransferFunds transferFunds,
                       AccountNumber clearingAccountNumber) {
        this.accounts = Objects.requireNonNull(accounts, "accounts cannot be null");
        this.transferFunds = Objects.requireNonNull(transferFunds, "transferFunds cannot be null");
        this.clearingAccountNumber = Objects.requireNonNull(clearingAccountNumber, "clearingAccountNumber cannot be null");
    }

    public Account open(OpenAccountCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        if (accounts.findByNumber(command.number()).isPresent()) {
            throw new DuplicateAccountNumberException(command.number());
        }

        boolean funded = !command.openingBalance().isZero();
        if (funded && accounts.findByNumber(clearingAccountNumber).isEmpty()) {
            throw new ClearingAccountMissingException(clearingAccountNumber);
        }

        Account newAccount = Account.open(command.number(), Money.ZERO);
        accounts.save(newAccount);

        if (funded) {
            transferFunds.transfer(new TransferCommand(
                    clearingAccountNumber,
                    command.number(),
                    command.openingBalance()
            ));
        }

        return accounts.findByNumber(command.number())
                .orElseThrow(() -> new IllegalStateException(
                        "just-opened account vanished: " + command.number().value()));
    }
}
