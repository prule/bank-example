package com.bank.core.application.transfer;

import com.bank.core.domain.Money;
import java.util.Objects;

/**
 * Command representing a request to transfer funds between two accounts.
 */
public record TransferCommand(
    String sourceAccountNumber,
    String destinationAccountNumber,
    Money amount
) {
    public TransferCommand {
        Objects.requireNonNull(sourceAccountNumber, "Source account number must not be null");
        Objects.requireNonNull(destinationAccountNumber, "Destination account number must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        if (sourceAccountNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Source account number must not be empty");
        }
        if (destinationAccountNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Destination account number must not be empty");
        }
    }
}
