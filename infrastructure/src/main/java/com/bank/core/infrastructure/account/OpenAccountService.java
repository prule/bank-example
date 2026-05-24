package com.bank.core.infrastructure.account;

import com.bank.core.application.account.OpenAccount;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Transactional shell for the F08 account-opening use case — the equivalent
 * of F06's {@code TransferController} for the no-HTTP path. F08 has no
 * controller; this {@code @Service @Transactional} facade owns the single
 * logical transactional boundary the {@code account-opening} spec requires.
 *
 * <h2>Every caller MUST inject this class, NOT {@link OpenAccount} directly</h2>
 * F09 (dev data seeding), the eventual HTTP controller, and the integration
 * test suite all inject this facade. Direct injection of {@link OpenAccount}
 * from anywhere outside the application-module unit test would lose the
 * {@code @Transactional} guarantee and break the spec scenario
 * "Atomicity is enforced by the infrastructure facade, not the application
 * use case" — the new account would be persisted but a failed funding
 * transfer would not roll it back.
 *
 * <h2>One method, one delegate</h2>
 * The facade is deliberately tiny: constructor, single public method, no
 * logging, no validation, no decision logic. A future refactor that splits
 * orchestration across multiple beans would defeat the single-transaction
 * guarantee. All decision logic belongs in {@link OpenAccount}.
 */
@Service
@Transactional
public class OpenAccountService {

    private final OpenAccount openAccount;

    public OpenAccountService(OpenAccount openAccount) {
        this.openAccount = Objects.requireNonNull(openAccount, "openAccount cannot be null");
    }

    public Account open(OpenAccountCommand command) {
        return openAccount.open(command);
    }
}
