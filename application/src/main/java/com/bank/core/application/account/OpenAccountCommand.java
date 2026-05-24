package com.bank.core.application.account;

import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;

import java.util.Objects;

/**
 * Application-layer command carrying the two inputs of an account-opening
 * operation: the externally visible account number for the new account and
 * the opening balance (which MAY be zero).
 *
 * <p>Compact constructor rejects null fields. There is no range check on
 * {@code openingBalance}: a negative literal cannot construct a {@link Money}
 * at all (rejected by {@code Money.of(...)}), so the only invariant the
 * command itself enforces is non-null. Zero is explicitly allowed — see the
 * "Open with zero balance creates an Active account" scenario in
 * [[account-opening]] — and skips the F06 funding path.
 *
 * <p>Consumed by {@code OpenAccount.open(...)}; constructed by the eventual
 * F09 dev-data seeder and by any future HTTP account-opening controller.
 */
public record OpenAccountCommand(AccountNumber number, Money openingBalance) {

    public OpenAccountCommand {
        Objects.requireNonNull(number, "number cannot be null");
        Objects.requireNonNull(openingBalance, "openingBalance cannot be null");
    }
}
