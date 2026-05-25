## ADDED Requirements

### Requirement: Index endpoint advertises root resources

The service SHALL expose `GET /api/v1` returning HTTP `200` with a body whose only field is `_links`, a HAL-style map containing exactly the relations `self`, `accounts`, `transfers`, and `openapi`. `accounts` SHALL be a templated link (`templated: true`) whose `href` is the URI template `/api/v1/accounts/{accountNumber}`. All other links SHALL be non-templated absolute paths. The endpoint SHALL be generated from the OpenAPI contract; its controller SHALL implement the generated `IndexApi.getIndex(...)` interface.

#### Scenario: Index returns 200 with the four root links

- **WHEN** a client requests `GET /api/v1`
- **THEN** the response is HTTP 200 with `_links` containing `self` → `/api/v1`, `accounts` → `/api/v1/accounts/{accountNumber}` with `templated: true`, `transfers` → `/api/v1/transfers`, and `openapi` → `/v3/api-docs`
- **AND** the body contains no other top-level fields

#### Scenario: Index is discoverable from the OpenAPI document

- **WHEN** the canonical OpenAPI document served at `/v3/api-docs` is inspected
- **THEN** it declares `GET /api/v1` with `operationId: getIndex`, a 200 response referencing an `IndexResponse` schema, and that schema's only required field is `_links` of type `Links` (a map of `Link` objects)

### Requirement: Reusable Link schema

The OpenAPI contract SHALL declare a single reusable `Link` schema with required field `href` (string) and optional fields `templated` (boolean) and `title` (string). Every embedded `_links` object on every resource SHALL use this schema for its values.

#### Scenario: Link schema is reused across resources

- **WHEN** the OpenAPI document is inspected
- **THEN** the `components.schemas` block contains exactly one `Link` definition with required `href` and optional `templated` + `title`, and every resource schema with a `_links` member references that schema (not an inline duplicate)

#### Scenario: Templated link is flagged

- **WHEN** a Link's `href` contains a `{var}` placeholder
- **THEN** the same link object SHALL set `templated: true`

#### Scenario: Non-templated link omits the flag

- **WHEN** a Link's `href` is a concrete path with no placeholders
- **THEN** the link object SHALL omit `templated` (or set it to `false`)

### Requirement: HAL content type negotiation

Every endpoint that returns a resource carrying `_links` SHALL respond with `Content-Type: application/hal+json` when the request's `Accept` header includes `application/hal+json`, and with `Content-Type: application/json` when the request's `Accept` header is `application/json` or `*/*`. The response body bytes SHALL be identical between the two content-types (`_links` is plain JSON).

#### Scenario: HAL-aware client gets hal+json

- **WHEN** a client requests `GET /api/v1` with `Accept: application/hal+json`
- **THEN** the response `Content-Type` is `application/hal+json` and the body is the same JSON document as with `Accept: application/json`

#### Scenario: Default client gets json

- **WHEN** a client requests `GET /api/v1` with no `Accept` header (defaults to `*/*`)
- **THEN** the response `Content-Type` is `application/json` and the status is 200

### Requirement: Link URIs constructed from controller method references

Server-side, every link's `href` SHALL be generated using Spring HATEOAS `WebMvcLinkBuilder.linkTo(methodOn(...))` (or equivalent type-safe builder) rather than hand-typed string literals, so that renaming a controller method or path declaration causes a compile-time break instead of a silent broken link.

#### Scenario: Link generation does not embed hardcoded paths

- **WHEN** the codebase under `com.bank.core.infrastructure.web` is inspected
- **THEN** no link `href` value is built by concatenating `"/api/v1/..."` string literals; every link uses `WebMvcLinkBuilder.linkTo(...)` or a documented helper that delegates to it

#### Scenario: Renaming a controller method breaks the build

- **WHEN** a developer renames a controller method that is referenced by `methodOn(...)` from a link builder
- **THEN** `./gradlew build` fails at Java compilation (not at runtime via 404) because the method reference no longer resolves

### Requirement: Self link present on every linked resource

Every resource representation that carries a `_links` map SHALL include at least one entry with the relation `self` whose `href` resolves to the canonical URL of that resource. For collection or query endpoints (none exist yet but the rule is forward-looking), `self` SHALL include the full query.

#### Scenario: Account response carries self link

- **WHEN** a client reads `GET /api/v1/accounts/CUST-1001`
- **THEN** the response body's `_links.self.href` is `/api/v1/accounts/CUST-1001`

#### Scenario: Index response carries self link

- **WHEN** a client reads `GET /api/v1`
- **THEN** the response body's `_links.self.href` is `/api/v1`

## MODIFIED Requirements
<!-- none — modifications to account-lookup live in that capability's delta spec file -->

## REMOVED Requirements
<!-- none -->
