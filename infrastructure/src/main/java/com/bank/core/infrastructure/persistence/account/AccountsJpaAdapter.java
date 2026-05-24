package com.bank.core.infrastructure.persistence.account;

import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
class AccountsJpaAdapter implements Accounts {

    private final AccountRepository repository;

    AccountsJpaAdapter(AccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findByNumber(AccountNumber number) {
        return repository.findByAccountNumber(number.value()).map(AccountMapper::toDomain);
    }

    @Override
    @Transactional
    public Account save(Account account) {
        AccountEntity existing = repository.findById(account.id().value()).orElse(null);
        if (existing == null) {
            repository.save(AccountMapper.toEntity(account));
        } else {
            // Mutable aggregate update path: balance and status may change as
            // transfers commit. account_number and id are not updatable
            // (column updatable=false enforces it at the JPA layer).
            existing.setBalance(account.balance().toBigDecimal());
            existing.setStatus(account.status());
        }
        return account;
    }
}
