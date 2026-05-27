# HATEOAS Discovery

## Purpose

Make the running service self-describing. Every resource that has a stable URI carries HAL-style `_links`, and a single index resource at `GET /api/v1` advertises the root of the API so a fresh client can bootstrap without reading the OpenAPI document. Owns the contract for the index endpoint, the reusable `Link` shape, HAL content negotiation, type-safe link construction, and the universal `self`-link rule. Builds on [[contract-first-api]] (OpenAPI as source of truth) and is consumed by [[account-lookup]] which extends its response shape to include `_links`.

## Requirements

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

Server-side, every link's `href` SHALL be generated using Spring HATEOAS `WebMvcLinkBuilder.linkTo(methodOn(...))` (or equivalent type-safe builder) inside a `RepresentationModelAssembler`. Hand-typed URL string literals SHALL NOT be concatenated to produce link `href` values. Renaming a referenced controller method SHALL cause Java compilation to fail at the call site rather than producing a silent broken link at runtime.

#### Scenario: Link generation does not embed hardcoded paths

- **WHEN** the codebase under `com.bank.core.infrastructure.web` is inspected
- **THEN** no link `href` value is built by concatenating `"/api/v1/..."` string literals; every link is built via `WebMvcLinkBuilder.linkTo(methodOn(...))` inside an assembler class

#### Scenario: Renaming a controller method breaks the build

- **WHEN** a developer renames a controller method that is referenced by `methodOn(...)` from an assembler
- **THEN** `./gradlew build` fails at Java compilation (not at runtime via 404) because the method reference no longer resolves

#### Scenario: Link construction is not duplicated across endpoints

- **WHEN** two endpoints need to return the same linked resource type
- **THEN** they both depend on the same assembler component for response construction
- **AND** the link-building logic is written exactly once, in that assembler

### Requirement: Assembler ownership of linked response construction

For every endpoint that returns a resource carrying `_links`, the controller SHALL delegate construction of the response body — both the data fields and the `_links` payload — to a single Spring `@Component` whose class name ends in `ModelAssembler` and which exposes a `toModel(...)` method returning the populated response DTO. The controller SHALL NOT instantiate any `*ResponseLinks` DTO directly, SHALL NOT call `WebMvcLinkBuilder` directly, and SHALL NOT call `setLinks(...)` on a response DTO. The assembler SHALL be the single class that knows which relations a given resource carries.

Note: the assembler classes do not `implements org.springframework.hateoas.server.RepresentationModelAssembler` because that interface bounds its output type to `extends RepresentationModel<?>`, which the OpenAPI-generated response DTOs (owned by [[contract-first-api]]) do not satisfy. The assembler *pattern* — a single class owning the domain→response transformation including links — is what this requirement enforces, not the framework marker interface.

#### Scenario: Controller delegates to assembler

- **WHEN** the codebase under `com.bank.core.infrastructure.web` is inspected
- **THEN** `AccountController.lookupAccount(...)` contains no reference to `AccountResponseLinks`, `WebMvcLinkBuilder`, or `methodOn(...)`
- **AND** it injects an `AccountModelAssembler` and obtains its response body solely by calling that assembler
- **AND** the same constraints hold for `IndexController.getIndex()` with respect to `IndexResponseLinks` and `IndexModelAssembler`

#### Scenario: Each linked response type has exactly one assembler

- **WHEN** the codebase under `com.bank.core.infrastructure.web` is inspected for Spring `@Component` classes whose names end in `ModelAssembler`
- **THEN** exactly one assembler exists per generated response DTO that carries `_links` — currently `AccountModelAssembler` for `AccountResponse` and `IndexModelAssembler` for `IndexResponse`
- **AND** no two assemblers produce the same response DTO type

#### Scenario: Assembler is unit-testable without MockMvc

- **WHEN** the assembler unit test runs
- **THEN** it instantiates the assembler directly (no Spring context, no `MockMvc`), passes in a domain object (or, for the index assembler, no input), and asserts the resulting populated DTO has the expected field values and link `href` values
- **AND** the test does not start a web server or load a `@SpringBootTest` context

### Requirement: Assembler-internal use of WebMvcLinkBuilder

Each assembler SHALL construct every `_links` `href` using `WebMvcLinkBuilder.linkTo(methodOn(...))` (or equivalent type-safe builder). The compile-time-safety guarantee that renaming a referenced controller method breaks the build SHALL be preserved.

#### Scenario: Renaming a controller method breaks the build via the assembler

- **WHEN** a developer renames a controller method that an assembler references through `methodOn(...)`
- **THEN** `./gradlew build` fails at Java compilation in the assembler class (not at runtime, not via a 404)

#### Scenario: No string-concatenated paths in assemblers

- **WHEN** the source of every class implementing `RepresentationModelAssembler` is inspected
- **THEN** no `_links` `href` value is built by concatenating `"/api/v1/..."` string literals
- **AND** every concrete (non-templated) link `href` is derived from a `WebMvcLinkBuilder.linkTo(methodOn(...))` call

### Requirement: Removal of standalone link helper

The repository SHALL NOT contain `com.bank.core.infrastructure.web.LinkFactory`. Link helper logic, if any, SHALL live inside an assembler or a `RepresentationModelAssemblerSupport` subclass.

#### Scenario: LinkFactory is gone

- **WHEN** the source tree under `infrastructure/src/main/java/com/bank/core/infrastructure/web/` is inspected
- **THEN** no file named `LinkFactory.java` exists
- **AND** no class in the module imports or references `com.bank.core.infrastructure.web.LinkFactory`

### Requirement: Self link present on every linked resource

Every resource representation that carries a `_links` map SHALL include at least one entry with the relation `self` whose `href` resolves to the canonical URL of that resource. For collection or query endpoints (none exist yet but the rule is forward-looking), `self` SHALL include the full query.

#### Scenario: Account response carries self link

- **WHEN** a client reads `GET /api/v1/accounts/CUST-1001`
- **THEN** the response body's `_links.self.href` is `/api/v1/accounts/CUST-1001`

#### Scenario: Index response carries self link

- **WHEN** a client reads `GET /api/v1`
- **THEN** the response body's `_links.self.href` is `/api/v1`
