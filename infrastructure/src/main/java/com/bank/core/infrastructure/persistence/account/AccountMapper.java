package com.bank.core.infrastructure.persistence.account;

import com.bank.core.domain.Account;
import com.bank.core.domain.AccountId;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.Money;

final class AccountMapper {

    private AccountMapper() {
    }

    static AccountEntity toEntity(Account account) {
        return new AccountEntity(
                account.id().value(),
                account.number().value(),
                account.balance().toBigDecimal(),
                account.status()
        );
    }

    static Account toDomain(AccountEntity entity) {
        return Account.rehydrate(
                AccountId.of(entity.getId()),
                AccountNumber.of(entity.getAccountNumber()),
                Money.of(entity.getBalance()),
                entity.getStatus()
        );
    }
}
