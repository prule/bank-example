package com.bank.core.application.seed;

import com.bank.core.application.account.Accounts;
import com.bank.core.application.account.OpenAccountCommand;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the F09 dev-data seed: materialise the clearing account
 * directly, then open every configured customer account through F08's
 * transactional pipeline so each customer is funded by a real journal entry
 * from the clearing account.
 *
 * <h2>Idempotency</h2>
 * The sole idempotency guard is the clearing-account pre-check. If the
 * clearing-account row is present on entry, {@link #seed()} returns
 * {@link SeedReport.Skipped} immediately without examining the customer list
 * or touching any other state. Re-running the seeder against an
 * already-seeded database is therefore a strict no-op regardless of
 * subsequent plan edits — the spec accepts that a config change after the
 * first successful seed requires wiping the schema rather than reconciling.
 *
 * <h2>Atomicity is per-customer, not whole-plan</h2>
 * Each {@link OpensAccount#open(OpenAccountCommand)} call inherits F08's
 * single-transaction guarantee, so a failed customer open rolls back its
 * own create + fund without affecting prior customers that already committed
 * in this run. The runner does not wrap the whole loop in an outer
 * transaction: that would hold F07 paired locks across every customer open
 * and would require F06 to read uncommitted clearing-account writes. The
 * trade-off is documented in {@code design.md} (Decision 3).
 *
 * <h2>Why the clearing account is created directly, not through F08</h2>
 * F08's {@code OpenAccount.open(...)} requires a clearing-account row to
 * exist before it will fund any positive opening balance. The clearing
 * account itself is the genesis row: there is no prior account from which
 * to fund it. F09 is the one legitimate place in the system that persists
 * an account with a non-zero balance without a matching ledger movement;
 * see {@link ClearingAccountSeed} and the {@code dev-data-seeding} spec.
 */
public final class SeedData {

    private final Accounts accounts;
    private final OpensAccount opensAccount;
    private final SeedPlan plan;

    public SeedData(Accounts accounts, OpensAccount opensAccount, SeedPlan plan) {
        this.accounts = Objects.requireNonNull(accounts, "accounts cannot be null");
        this.opensAccount = Objects.requireNonNull(opensAccount, "opensAccount cannot be null");
        this.plan = Objects.requireNonNull(plan, "plan cannot be null");
    }

    public SeedReport seed() {
        AccountNumber clearingNumber = plan.clearingAccount().number();
        if (accounts.findByNumber(clearingNumber).isPresent()) {
            return new SeedReport.Skipped("clearing account already present");
        }

        Account clearingAccount = Account.open(
                clearingNumber,
                plan.clearingAccount().openingBalance());
        accounts.save(clearingAccount);

        List<AccountNumber> opened = new ArrayList<>(plan.customers().size());
        for (CustomerSeed customer : plan.customers()) {
            opensAccount.open(new OpenAccountCommand(customer.number(), customer.openingBalance()));
            opened.add(customer.number());
        }

        return new SeedReport.Seeded(clearingNumber, opened);
    }
}
