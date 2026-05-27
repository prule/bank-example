package com.bank.core.application.account;

import com.bank.core.application.transfer.TransferCommand;
import com.bank.core.application.transfer.TransferFunds;
import com.bank.core.domain.Account;
import com.bank.core.domain.ClearingAccountMissingException;
import com.bank.core.domain.DuplicateAccountNumberException;
import com.bank.core.domain.Money;
import com.bank.core.domain.ResourceNotFoundException;

import java.util.Objects;

/**
 * Spring-free application use case that coordinates the creation and initial funding of customer accounts.
 */
public final class OpenAccount {
    private final Accounts accounts;
    private final TransferFunds transferFunds;
    private final String clearingAccountNumber;

    public OpenAccount(Accounts accounts, TransferFunds transferFunds, String clearingAccountNumber) {
        this.accounts = Objects.requireNonNull(accounts, "Accounts port must not be null");
        this.transferFunds = Objects.requireNonNull(transferFunds, "TransferFunds must not be null");
        this.clearingAccountNumber = Objects.requireNonNull(clearingAccountNumber, "Clearing account number must not be null");
    }

    /**
     * Opens a new customer account, optionally funding it with an opening balance.
     *
     * @param command the open account command containing chosen number and opening balance
     * @return the reloaded customer Account aggregate reflecting any committed opening balance
     * @throws DuplicateAccountNumberException if the account number already exists
     * @throws ClearingAccountMissingException if a positive balance is requested but clearing account does not exist
     */
    public Account open(OpenAccountCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Early duplicate account pre-check to reject before any persistence actions
        if (accounts.findByNumber(command.number()).isPresent()) {
            throw new DuplicateAccountNumberException(command.number());
        }

        // 2. Early clearing account verification (precondition applies to positive opens only)
        boolean hasOpeningBalance = command.openingBalance().isGreaterThan(Money.ZERO);
        if (hasOpeningBalance) {
            if (accounts.findByNumber(clearingAccountNumber).isEmpty()) {
                throw new ClearingAccountMissingException(clearingAccountNumber);
            }
        }

        // 3. Create the new customer account aggregate at balance zero
        Account newAccount = Account.open(command.number(), Money.ZERO);
        accounts.save(newAccount);

        // 4. Perform funding transfer from clearing account if balance > 0
        if (hasOpeningBalance) {
            TransferCommand transferCommand = new TransferCommand(
                    clearingAccountNumber,
                    command.number(),
                    command.openingBalance()
            );
            transferFunds.transfer(transferCommand);
        }

        // 5. Reload aggregate from port so returned state is fresh and correct
        return accounts.findByNumber(command.number())
                .orElseThrow(() -> new ResourceNotFoundException("account", command.number()));
    }
}
