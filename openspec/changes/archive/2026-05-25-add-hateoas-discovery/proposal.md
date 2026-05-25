## Why

Today the bank-core API forces clients to hard-code URI templates: a caller who reads `GET /api/v1/accounts/{accountNumber}` learns nothing about how to initiate a transfer or where to fetch the contract. Discovery happens only out-of-band via `openapi.yaml`. HATEOAS links make the running service self-describing — responses carry hypermedia pointing at the next legal interactions, and an index endpoint at `/api/v1` advertises the root resources. This decouples client code from URL conventions and matches the contract-first ethos already established for [contract-first-api](../../specs/contract-first-api/spec.md).

## What Changes

- Add a new index endpoint: `GET /api/v1` returning a HAL-style `_links` object with entries for `self`, `accounts` (templated URI), `transfers`, and `openapi`
- Extend `AccountResponse` with a `_links` object: `self` (this resource) and `transfers` (the transfer endpoint to initiate movement from this account)
- Define a reusable `Link` schema in the OpenAPI contract (`href`, optional `templated`, optional `title`) used by every embedded `_links` block
- Use `application/hal+json` as the response content-type for resources carrying `_links` (alongside existing `application/json` for backward compatibility — controllers SHALL serve both)
- Generate link URIs server-side with Spring HATEOAS `WebMvcLinkBuilder` so paths stay anchored to controller method references, not hand-typed strings
- **BREAKING:** `AccountResponse` gains a new required field `_links`. Existing clients that strictly reject unknown fields will need to relax that, but the additional field is backward-tolerant for clients that ignore unknown JSON keys (the common case).

## Capabilities

### New Capabilities
- `hateoas-discovery`: index endpoint and link-embedding contract that lets clients discover the API root and traverse from any returned resource to the next legal interaction.

### Modified Capabilities
- `account-lookup`: the lookup response body now includes a `_links` object alongside the existing fields.

## Impact

- Modified OpenAPI files: `openapi.yaml` (new path + new schema), `schemas/account-response.yaml` (add `_links`), new `schemas/link.yaml`, new `schemas/index-response.yaml`, new `paths/index.yaml`
- New generated Java types: `IndexApi`, `IndexResponse`, `Link`
- New controller: `IndexController` implementing `IndexApi` in `com.bank.core.infrastructure.web`
- Modified controller: `AccountController` (populates `_links` on each response)
- New runtime dependency: `org.springframework.boot:spring-boot-starter-hateoas` in `infrastructure` (and/or `bootstrap`) module
- Updated tests: `AccountLookupControllerTest`, `OpenApiContractTest`, new `IndexControllerTest`
- Documentation in [ReadMe.md](../../../ReadMe.md) gains a "Discovery" section pointing at `GET /api/v1`
- No domain or persistence changes; this is an HTTP-layer enhancement
