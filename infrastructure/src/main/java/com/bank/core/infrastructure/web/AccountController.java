package com.bank.core.infrastructure.web;

import com.bank.core.api.AccountsApi;
import com.bank.core.application.account.Accounts;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.dto.AccountResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing GET lookup API for bank accounts.
 * Delegates DTO mapping and HATEOAS HAL link generation to AccountModelAssembler.
 */
@RestController
public class AccountController implements AccountsApi {

    private final Accounts accounts;
    private final AccountModelAssembler accountModelAssembler;

    public AccountController(Accounts accounts, AccountModelAssembler accountModelAssembler) {
        this.accounts = accounts;
        this.accountModelAssembler = accountModelAssembler;
    }

    @Override
    @GetMapping(value = "/api/v1/accounts/{accountNumber}", produces = { "application/json", "application/hal+json" })
    public ResponseEntity<AccountResponse> lookupAccount(
            @PathVariable("accountNumber") String accountNumber
    ) {
        AccountResponse response = accounts.findByNumber(accountNumber)
                .map(accountModelAssembler::toModel)
                .orElseThrow(() -> new ResourceNotFoundException("account", accountNumber));
        return ResponseEntity.ok(response);
    }
}
