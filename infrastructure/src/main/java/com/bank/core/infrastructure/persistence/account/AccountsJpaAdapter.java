package com.bank.core.infrastructure.persistence.account;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.Money;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public class AccountsJpaAdapter implements Accounts {
    private final AccountRepository accountRepository;

    public AccountsJpaAdapter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findByNumber(String number) {
        return accountRepository.findByAccountNumber(number)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findById(AccountId id) {
        return accountRepository.findById(id.toString())
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public Account save(Account account) {
        AccountEntity entity = toEntity(account);
        accountRepository.save(entity);
        return account;
    }

    private Account toDomain(AccountEntity entity) {
        return Account.rehydrate(
                AccountId.fromString(entity.getId()),
                entity.getAccountNumber(),
                Money.of(entity.getBalance()),
                entity.getStatus()
        );
    }

    private AccountEntity toEntity(Account domain) {
        Instant createdAt = accountRepository.findById(domain.getId().toString())
                .map(AccountEntity::getCreatedAt)
                .orElseGet(Instant::now);

        return new AccountEntity(
                domain.getNumber(),
                domain.getId().toString(),
                domain.getBalance().asBigDecimal(),
                domain.getStatus(),
                createdAt
        );
    }
}
