package com.bank.core.application.transfer;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.InvalidAmountException;
import com.bank.core.domain.Money;

import java.util.Objects;

/**
 * Application-layer command carrying the three inputs of a fund transfer:
 * the externally visible source and destination account numbers and the
 * positive amount to move.
 *
 * Defence-in-depth: the compact constructor rejects null fields and
 * zero amounts even though the OpenAPI bean-validation layer should catch
 * these before the controller calls the use case.
 */
public record TransferCommand(AccountNumber source, AccountNumber destination, Money amount) {

    public TransferCommand {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(destination, "destination cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        if (amount.isZero()) {
            throw new InvalidAmountException("transfer amount must be positive");
        }
    }
}
