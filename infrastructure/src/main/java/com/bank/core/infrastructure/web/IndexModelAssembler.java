package com.bank.core.infrastructure.web;

import com.bank.core.dto.IndexResponse;
import com.bank.core.dto.IndexResponseLinks;
import com.bank.core.dto.Link;
import com.bank.core.infrastructure.web.account.AccountController;
import com.bank.core.infrastructure.web.transfer.TransferController;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Single owner of the {@link IndexResponse} body for {@code GET /api/v1},
 * including the {@code _links} payload. {@link IndexController} depends on
 * this assembler and contains no link-building knowledge.
 *
 * <p>The index endpoint takes no input, so this assembler does not implement
 * {@link org.springframework.hateoas.server.RepresentationModelAssembler}
 * (which is defined over an input domain type). It exposes a {@link #toModel()}
 * method instead. See the {@link AccountModelAssembler} class Javadoc for the
 * reasoning behind keeping the OpenAPI-generated DTO as the return type
 * rather than a {@code RepresentationModel} subtype.
 *
 * <p>The {@code accounts} relation is a URI Template ({@code /api/v1/accounts/{accountNumber}})
 * and is constructed inline here because {@code methodOn(...)} produces a
 * concrete path with a placeholder substituted in. The other three relations
 * are built from {@code linkTo(methodOn(...))} so renaming a referenced
 * controller method breaks compilation at this assembler.
 */
@Component
public class IndexModelAssembler {

    public IndexResponse toModel() {
        Link self = concreteLink(linkTo(methodOn(IndexController.class).getIndex()).toUri().getPath());
        Link accounts = new Link("/api/v1/accounts/{accountNumber}").templated(true);
        Link transfers = concreteLink(linkTo(methodOn(TransferController.class).createTransfer(null, null)).toUri().getPath());
        Link openapi = concreteLink(linkTo(methodOn(OpenApiController.class).getOpenApiDocument()).toUri().getPath());
        return new IndexResponse(new IndexResponseLinks(self, accounts, transfers, openapi));
    }

    private static Link concreteLink(String href) {
        return new Link(href).templated(false);
    }
}
