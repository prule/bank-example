package com.bank.core.infrastructure.web;

import com.bank.core.domain.Account;
import com.bank.core.dto.AccountResponse;
import com.bank.core.dto.AccountResponseLinks;
import com.bank.core.dto.Link;
import com.bank.core.infrastructure.web.transfer.TransferController;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Spring Component that maps Account domain aggregates to AccountResponse DTOs.
 * Handles HAL HATEOAS link creation in a compile-safe manner using WebMvcLinkBuilder.
 */
@Component
public class AccountModelAssembler {

    public AccountResponse toModel(Account account) {
        String selfHref = linkTo(methodOn(AccountController.class)
                .lookupAccount(account.getNumber()))
                .toUri()
                .getPath();

        String transfersHref = linkTo(methodOn(TransferController.class)
                .transferFunds(null))
                .toUri()
                .getPath();

        Link selfLink = new Link(selfHref);
        Link transfersLink = new Link(transfersHref);

        AccountResponseLinks links = new AccountResponseLinks(selfLink, transfersLink);

        AccountResponse.StatusEnum statusEnum = AccountResponse.StatusEnum.valueOf(account.getStatus().name());

        return new AccountResponse(
                account.getNumber(),
                account.getBalance().asBigDecimal().setScale(2).toString(),
                statusEnum,
                links
        );
    }
}
