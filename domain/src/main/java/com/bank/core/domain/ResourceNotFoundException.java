package com.bank.core.domain;

public class ResourceNotFoundException extends DomainException {
    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(String.format("Could not find %s with identifier %s", resourceType, identifier));
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
