package com.bank.core.infrastructure.web.account;

import com.bank.core.domain.Account;
import com.bank.core.dto.AccountResponse;
import org.springframework.stereotype.Component;

@Component
class AccountResponseMapper {

    AccountResponse toResponse(Account account) {
        AccountResponse response = new AccountResponse();
        response.setAccountNumber(account.number().value());
        response.setBalance(account.balance().toBigDecimal().toPlainString());
        response.setStatus(AccountResponse.StatusEnum.valueOf(account.status().name()));
        return response;
    }
}
