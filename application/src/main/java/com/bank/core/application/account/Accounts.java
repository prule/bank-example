package com.bank.core.application.account;

import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountNumber;

import java.util.Optional;

/**
 * Port for loading and persisting {@link Account} aggregates by their external
 * identifier ({@link AccountNumber}).
 *
 * Plain Java by design: no Spring annotations, no JPA types, no
 * openapi-generated imports. F00's ArchUnit boundary rule pins the application
 * module to this shape. The implementation lives in the infrastructure module
 * under {@code com.bank.core.infrastructure.persistence.account}.
 *
 * Downstream consumers:
 * <ul>
 *   <li>F05 (account lookup) uses {@link #findByNumber(AccountNumber)} for the
 *       read endpoint.</li>
 *   <li>F06 (fund transfer) loads source and destination via
 *       {@link #findByNumber(AccountNumber)} inside the F07 paired-locks
 *       block, mutates them via the domain mutators, and persists the result
 *       via {@link #save(Account)} — all inside one transactional boundary.</li>
 *   <li>F08 (account opening) calls {@link #save(Account)} for freshly minted
 *       aggregates from {@code Account.open(...)}, then funds them via the
 *       F06 transfer pipeline.</li>
 *   <li>F09 (dev data seeding) calls {@link #save(Account)} from a startup
 *       runner gated by {@code SEED_DATA=true}; idempotency is provided by
 *       the {@code account_number} unique index.</li>
 *   <li>F10 (journal verification) calls {@link #findById(AccountId)} from
 *       the suspend cascade — when an unbalanced journal is detected, every
 *       account referenced by the journal's movements is loaded by its
 *       internal {@link AccountId} and (if Active) suspended via
 *       {@link #save(Account)}.</li>
 *   <li>F11 (balance drift detection) loads aggregates via
 *       {@link #findByNumber(AccountNumber)} to compare cached balance
 *       against the per-account ledger sum.</li>
 * </ul>
 */
public interface Accounts {

    Optional<Account> findByNumber(AccountNumber number);

    Optional<Account> findById(AccountId id);

    Account save(Account account);
}
