package com.bank.core.infrastructure.web;

import com.bank.core.dto.Link;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.stereotype.Component;

/**
 * Builds generated {@link Link} DTOs whose {@code href} values come from
 * {@link WebMvcLinkBuilder} so that controller method references — not
 * hand-typed URL strings — anchor every link. Renaming a controller method
 * or changing its mapping fails Java compilation in the link's call site
 * rather than producing a silent broken link at runtime.
 *
 * <p>Returns the {@code com.bank.core.dto.Link} DTO rather than Spring
 * HATEOAS's own {@code Link} type so the wire shape stays controlled by
 * the OpenAPI-generated DTO (single source of truth per the contract-first
 * capability).
 */
@Component
public class LinkFactory {

    /** Concrete, non-templated link to a fully-resolved controller invocation. */
    public Link to(Object controllerMethodInvocation) {
        String href = WebMvcLinkBuilder.linkTo(controllerMethodInvocation).toUri().getPath();
        return new Link(href).templated(false);
    }

    /** Templated link whose href contains RFC 6570 placeholders the client must expand. */
    public Link templated(String hrefTemplate) {
        return new Link(hrefTemplate).templated(true);
    }
}
