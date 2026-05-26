package com.bank.core.infrastructure.web.account;

import com.bank.core.domain.Account;
import com.bank.core.dto.AccountResponse;
import com.bank.core.dto.AccountResponseLinks;
import com.bank.core.dto.Link;
import com.bank.core.infrastructure.web.transfer.TransferController;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Single owner of the {@link Account} → {@link AccountResponse} transformation,
 * including the {@code _links} payload. Controllers depend on this assembler
 * and contain no link-building knowledge.
 *
 * <p><b>Why this is a plain class and not an
 * {@code implements RepresentationModelAssembler}.</b> The
 * {@code contract-first-api} capability makes the OpenAPI document the
 * source of truth for response shapes. {@code AccountResponse._links} is a
 * typed object with required {@code self} and {@code transfers} fields (see
 * {@link AccountResponseLinks}), not the open {@code _links} map that
 * Spring HATEOAS renders natively. Spring HATEOAS's
 * {@code RepresentationModelAssembler<T, D>} bounds {@code D extends RepresentationModel<?>},
 * so the OpenAPI-generated DTO cannot be its output type. We keep the
 * generated DTO so the wire shape stays byte-identical to the OpenAPI
 * schema (and {@code OpenApiContractTest} keeps passing), and adopt the
 * assembler <i>pattern</i> by structural shape — a Spring {@code @Component}
 * with {@code toModel(T)} and {@code toCollectionModel(Iterable<T>)} methods —
 * rather than by interface implementation. See
 * {@code openspec/changes/use-spring-hateoas-assemblers/design.md} (Decision 1)
 * for the full reasoning.
 *
 * <p>All link {@code href} values are derived from
 * {@link org.springframework.hateoas.server.mvc.WebMvcLinkBuilder#linkTo(Object)}
 * applied to {@link org.springframework.hateoas.server.mvc.WebMvcLinkBuilder#methodOn(Class, Object...)}
 * controller method references, so renaming a referenced controller method
 * breaks Java compilation at this assembler rather than producing a silent
 * runtime 404.
 */
@Component
public class AccountModelAssembler {

    private final AccountResponseMapper mapper;

    public AccountModelAssembler(AccountResponseMapper mapper) {
        this.mapper = mapper;
    }

    public AccountResponse toModel(Account account) {
        AccountResponse response = mapper.toResponse(account);
        Link self = concreteLink(linkTo(methodOn(AccountController.class)
                .lookupAccount(account.number().value())).toUri().getPath());
        Link transfers = concreteLink(linkTo(methodOn(TransferController.class)
                .createTransfer(null, null)).toUri().getPath());
        response.setLinks(new AccountResponseLinks(self, transfers));
        return response;
    }

    /**
     * Minimal collection-support shim: applies {@link #toModel(Account)} to
     * every input. Not used by any current endpoint; landed so a future
     * account-collection endpoint can call into the same assembler.
     */
    public List<AccountResponse> toCollectionModel(Iterable<Account> accounts) {
        List<AccountResponse> out = new ArrayList<>();
        for (Account account : accounts) {
            out.add(toModel(account));
        }
        return out;
    }

    private static Link concreteLink(String href) {
        return new Link(href).templated(false);
    }
}
