package com.bank.core.infrastructure.web.account;

import com.bank.core.api.AccountsApi;
import com.bank.core.application.account.Accounts;
import com.bank.core.domain.Account;
import com.bank.core.domain.AccountNumber;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.dto.AccountResponse;
import com.bank.core.dto.AccountResponseLinks;
import com.bank.core.dto.Link;
import com.bank.core.infrastructure.web.LinkFactory;
import com.bank.core.infrastructure.web.transfer.TransferController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
public class AccountController implements AccountsApi {

    private final Accounts accounts;
    private final AccountResponseMapper mapper;
    private final LinkFactory links;

    public AccountController(Accounts accounts, AccountResponseMapper mapper, LinkFactory links) {
        this.accounts = accounts;
        this.mapper = mapper;
        this.links = links;
    }

    @Override
    public ResponseEntity<AccountResponse> lookupAccount(String accountNumber) {
        Account account = accounts.findByNumber(AccountNumber.of(accountNumber))
                .orElseThrow(() -> new ResourceNotFoundException("account", accountNumber));
        AccountResponse response = mapper.toResponse(account);
        Link self = links.to(methodOn(AccountController.class).lookupAccount(account.number().value()));
        Link transfers = links.to(methodOn(TransferController.class).createTransfer(null));
        response.setLinks(new AccountResponseLinks(self, transfers));
        return ResponseEntity.ok(response);
    }
}
