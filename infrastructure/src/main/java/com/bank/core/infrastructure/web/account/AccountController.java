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
public class AccountController implements AccountsApi {

    private final Accounts accounts;
    private final AccountModelAssembler assembler;

    public AccountController(Accounts accounts, AccountModelAssembler assembler) {
        this.accounts = accounts;
        this.assembler = assembler;
    }

    @Override
    public ResponseEntity<AccountResponse> lookupAccount(String accountNumber) {
        Account account = accounts.findByNumber(AccountNumber.of(accountNumber))
                .orElseThrow(() -> new ResourceNotFoundException("account", accountNumber));
        return ResponseEntity.ok(assembler.toModel(account));
    }
}
