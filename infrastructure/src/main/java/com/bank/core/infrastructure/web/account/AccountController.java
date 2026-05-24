package com.bank.core.infrastructure.web.account;

import com.bank.core.api.AccountsApi;
import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.dto.AccountResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController implements AccountsApi {

    private final Accounts accounts;
    private final AccountResponseMapper mapper;

    AccountController(Accounts accounts, AccountResponseMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<AccountResponse> lookupAccount(String accountNumber) {
        Account account = accounts.findByNumber(AccountNumber.of(accountNumber))
                .orElseThrow(() -> new ResourceNotFoundException("account", accountNumber));
        return ResponseEntity.ok(mapper.toResponse(account));
    }
}
