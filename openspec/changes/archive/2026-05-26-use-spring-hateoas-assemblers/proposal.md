## Why

Today every controller that returns a linked resource builds links inline: it injects `LinkFactory`, calls `methodOn(...)` two or three times, and hand-assembles the generated `*ResponseLinks` DTO. `IndexController.getIndex()` and `AccountController.lookupAccount(...)` both follow this shape. The controllers are doing two jobs — HTTP orchestration and response-shape assembly — and the link-building knowledge lives at the call site rather than next to the response type.

This becomes pointed the moment a second endpoint needs to return an `AccountResponse` (a future account-listing endpoint, or transfer-response payloads that embed account references). Today every such endpoint would have to duplicate the same `links.to(methodOn(AccountController.class)...)` block. Spring HATEOAS's `RepresentationModelAssembler` pattern is purpose-built for this: a single assembler owns the domain → response transformation including links, controllers depend on the assembler, and reuse comes for free (including `toCollectionModel` for collection endpoints).

## What Changes

- Introduce one `RepresentationModelAssembler` per linked resource:
  - `AccountModelAssembler` — `Account` (domain) → `AccountResponse` (generated DTO) with `_links.self` and `_links.transfers` populated.
  - `IndexModelAssembler` — produces the populated `IndexResponse` for `GET /api/v1`.
- Refactor `AccountController.lookupAccount(...)` and `IndexController.getIndex()` to delegate the entire domain-to-response transformation (including link construction) to their assembler. Controllers retain only: parameter handling, calling the application port, mapping `Optional.empty()` to `ResourceNotFoundException`, and returning `ResponseEntity.ok(model)`.
- Each assembler internally uses Spring HATEOAS `WebMvcLinkBuilder.linkTo(methodOn(...))` so the existing type-safe-link-construction guarantee carries over unchanged.
- Retire `LinkFactory` once both call sites have moved. The class is small and has no behavioural role beyond `WebMvcLinkBuilder` indirection — the assemblers can call `WebMvcLinkBuilder` directly, and the `templated:false` default is a one-liner inside each assembler.
- Existing tests (`IndexControllerTest`, `AccountLookupControllerTest`, `OpenApiContractTest`) keep their assertions; only the seams under test change. Add focused unit tests for each assembler exercising it without `MockMvc`.

Out of scope:
- No change to the OpenAPI contract. `AccountResponse._links` and `IndexResponse._links` keep their typed-field shape (required `self` / `transfers` etc.) — assemblers build the generated `*Links` DTOs; we are not pivoting to HAL's open-map `_links` shape.
- No new endpoints. No collection endpoint added by this change — the assembler shape simply makes a future one cheap. The `toCollectionModel` capability is gained automatically by extending `RepresentationModelAssemblerSupport`, but is not exercised.
- No change to HAL content negotiation (still served via the existing Spring HATEOAS hypermedia config).
- No change to the `Link` DTO shape on the wire.

## Capabilities

### New Capabilities
<!-- None. This is a refactor of an existing capability. -->

### Modified Capabilities
- `hateoas-discovery`: tightens the link-construction requirement so it mandates assembler-based ownership of response transformation, not just "use `WebMvcLinkBuilder` somewhere". Adds a requirement that controllers SHALL NOT directly construct `_links` payload objects; the assembler is the only allowed producer.

## Impact

- **Code**:
  - New: `infrastructure/web/account/AccountModelAssembler.java`, `infrastructure/web/IndexModelAssembler.java`.
  - Edited: `IndexController`, `AccountController` shrink to thin orchestration shells.
  - Deleted: `infrastructure/web/LinkFactory.java` (after both call sites migrate).
- **Build / deps**: no new dependency. `spring-boot-starter-hateoas` is already on the infrastructure module's classpath; `RepresentationModelAssembler` and `RepresentationModelAssemblerSupport` ship inside it.
- **Wire**: byte-identical responses. `OpenApiContractTest` and the existing controller tests act as the regression gate.
- **Tests**: 2 new assembler unit tests; existing MockMvc tests unchanged in intent. No integration-test reshuffle.
- **No impact on**: domain module, application module, persistence layer, OpenAPI document, database, dev-data seeding, or any of `fund-transfer`, `journal-verification`, `balance-drift-detection`, `transfer-locking`.
