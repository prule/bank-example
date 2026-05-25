## Context

Contract-first OpenAPI project. Existing endpoints:

- `GET /api/v1/accounts/{accountNumber}` — returns `AccountResponse { accountNumber, balance, status }`
- `POST /api/v1/transfers` — 204
- `GET /v3/api-docs` — canonical OpenAPI document

Controllers hand-written, implementing generated interfaces (`AccountsApi`, `TransfersApi`). DTOs generated from `schemas/*.yaml`. Boundary tests (`OpenApiContractTest`, ArchUnit) keep contract and code in sync.

No discovery affordance today. A new client has to read `openapi.yaml` to learn endpoints. HATEOAS adds runtime discovery via embedded links and an index resource at the API root.

## Goals / Non-Goals

**Goals:**
- A single index endpoint `GET /api/v1` advertising every root resource the API exposes
- Every resource representation that has a stable URI carries a `_links.self` entry
- The account representation carries a `_links.transfers` entry pointing at the transfer endpoint so a client holding an account can immediately discover how to move money
- Use of standard HAL (`application/hal+json` + `_links`) so off-the-shelf clients (curl + jq, Spring HATEOAS clients, hypermedia libraries) work without bespoke parsing
- Link URLs constructed from controller references, not string concatenation

**Non-Goals:**
- Full HAL `_embedded` support (embedding subresources inline) — defer
- Action affordances (HAL-FORMS / Siren / JSON:API actions) — defer; clients still POST via documented contract
- Adding links to 204 responses (transfer success) — keep transfer body empty; clients re-read accounts (existing pattern from [fund-transfer](../../specs/fund-transfer/spec.md))
- Adding links to error responses — `ErrorEnvelope` stays untouched
- Pagination / collection resources (no list endpoints exist yet)
- Versioned media types (`application/vnd.bank.v1+hal+json`) — `application/hal+json` is enough for this hop

## Decisions

**HAL (`_links` shape) over JSON:API / Siren / HAL-FORMS**
HAL is the lightest mainstream hypermedia format; Spring HATEOAS supports it out of the box; minimal schema churn. Alternative: JSON:API — much heavier shape (top-level `data`, `relationships`, `included`), rejected for the size of our API. Alternative: Siren — requires nested `entities`/`actions` not justified here.

**Define HAL types in OpenAPI by hand, not via `spring-hateoas` generated DTOs**
Two reasons:
1. The contract-first capability mandates that DTOs are generated from `schemas/*.yaml`. Mixing in Spring HATEOAS's `RepresentationModel`/`EntityModel` server-side would create a second source of truth that diverges from the OpenAPI document.
2. Clients are language-agnostic; the contract should describe the wire shape directly.

So we add `schemas/link.yaml` and an inline `_links` object on each resource schema. Spring HATEOAS is used **only** server-side as a URL-construction utility (`linkTo(methodOn(...))`), with the resulting `Link.getHref()` string copied into the generated DTO.

**`_links` field name and shape**
Strict HAL: `_links: { rel-name: { href, templated?, title? } }`. Same on every resource. Index uses the same structure — there is no special "root" envelope. This keeps the schema reusable.

**Index path: `GET /api/v1` (no trailing slash, returns 200, no auth)**
Sits one level under the version segment so the version is discoverable. Alternative: `/` — rejected, leaves no obvious place for future API versions to coexist. Alternative: `/api` — rejected, ambiguous between unversioned and v1.

**Content-Type: respond `application/hal+json` and `application/json`**
HAL clients send `Accept: application/hal+json` and expect that exact type back; naive clients send `Accept: application/json` (or `*/*`). Both must work. Implementation: controller annotated `produces = { "application/hal+json", "application/json" }`; the same JSON bytes are returned either way (`_links` is a vanilla JSON object, no HAL-specific bytes).

**Self-link URI builder: Spring HATEOAS `WebMvcLinkBuilder.linkTo(methodOn(...))`**
Anchors link URLs to controller method references so a typo or path rename is caught at compile time. Alternative: hardcode `"/api/v1/accounts/" + accountNumber` — rejected, brittle to refactor. Alternative: `ServletUriComponentsBuilder` — works but loses the method-reference safety net.

**`templated: true` for the accounts entry on the index**
The accounts resource is parameterised (`accountNumber`). Per RFC 6570 + HAL, that link is published as a URI template `/api/v1/accounts/{accountNumber}` with `templated: true`. Self-links on a concrete account are non-templated absolute paths.

**Field placement: `_links` last in the JSON body**
Keeps the existing fields (`accountNumber`, `balance`, `status`) in their familiar positions; `_links` appends. JSON object ordering doesn't matter for parsers but matters for human-readable diffs in tests and curl output.

## Risks / Trade-offs

- **Breaking change for strict-schema clients** → `AccountResponse` gains a required field. Mitigation: required from the server perspective (always populated); clients that use the generated DTO regenerate. Clients that hand-rolled a strict schema must add `_links`. Documented in the proposal's BREAKING note.
- **Spring HATEOAS dependency size + transitive surface** → `spring-boot-starter-hateoas` pulls in Jackson modules. Mitigation: only used server-side; tested via `OpenApiContractTest` that the wire shape matches the OpenAPI document we author by hand.
- **Drift between hand-authored OpenAPI link shape and Spring HATEOAS's serialization defaults** → Spring HATEOAS's `RepresentationModel` serializes `_links` as `Map<String, Link>` matching HAL — but we are NOT using it as a DTO. We build `Link.getHref()` and stuff it into our generated DTO. So serialization is controlled by our DTO, not the framework. No drift risk.
- **Index endpoint becomes a god-resource over time** → Acceptable for now; the index has 4 entries. If it grows past ~10 we revisit with curied links (`curies` HAL feature).
- **404 on `GET /api/v1` looks like the API is down** → Mitigation: this change adds the endpoint, so the failure mode disappears. Smoke test under `bootstrap`.

## Migration Plan

1. Land the OpenAPI schema changes
2. Add Spring HATEOAS dependency
3. Implement `IndexController` + populate `_links` on `AccountController`
4. Update controller tests
5. Run `./gradlew build` — the `OpenApiContractTest` should pass with the new contract; generated DTO changes propagate to compilation
6. No DB migration. No data backfill. Rollback = revert the commit.

## Open Questions

- Should the transfer endpoint return a `Location` header pointing at the source-account URL post-success? Useful affordance but stretches the 204 contract. Defer.
- Curie definitions for custom rels? Standard rels (`self`, `accounts`, `transfers`, `openapi`) suffice for now; revisit if we add non-IANA rels.
