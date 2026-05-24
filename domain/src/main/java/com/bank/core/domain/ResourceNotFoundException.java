package com.bank.core.domain;

import java.util.Objects;

/**
 * Signals that a lookup by external identifier did not match any persisted
 * resource. Generic over resource type ({@code "account"}, future
 * {@code "journal"}, etc.) so that one F03 exception-handler entry maps every
 * 404 case to the canonical {@code RESOURCE_NOT_FOUND} envelope.
 */
public final class ResourceNotFoundException extends DomainException {

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(Objects.requireNonNull(resourceType, "resourceType cannot be null")
                + " '" + Objects.requireNonNull(identifier, "identifier cannot be null") + "' not found");
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public String resourceType() {
        return resourceType;
    }

    public String identifier() {
        return identifier;
    }
}
