## Context

`IndexController` and `AccountController` each hand-assemble their `_links` block from inside the controller method. The shared helper `LinkFactory` saves a couple of lines per call site but does not separate the "which links does this resource carry?" question from the "how do I serve an HTTP request?" question — those two concerns sit in the same method, so they grow together.

Spring HATEOAS already ships an answer: `RepresentationModelAssembler<T, D extends RepresentationModel<?>>` and the `RepresentationModelAssemblerSupport` base class. The pattern places the domain-to-response transformation in a single component, including link construction, and gives `toCollectionModel(Iterable<T>)` for free. The project already depends on `spring-boot-starter-hateoas` (via `infrastructure/build.gradle.kts`), so the cost of adoption is structural, not dependency-driven.

The non-obvious constraint is contract-first-api: the OpenAPI document is the source of truth for response shapes, and the generated `AccountResponse` / `IndexResponse` DTOs (and their `*Links` siblings) carry typed `self` / `transfers` fields rather than HAL's generic `_links` map. Spring HATEOAS's preferred pattern is to extend `RepresentationModel<T>` and let the framework render `_links` as a map; here we must keep populating the generated DTOs to preserve byte-identical wire output and the existing OpenAPI contract.

## Goals / Non-Goals

**Goals:**
- One assembler per linked response type, owning the full transformation including link construction.
- Controllers reduced to HTTP orchestration: parse input, call application port, hand the result to the assembler, wrap in `ResponseEntity`.
- Type-safe link construction (`methodOn(...)`) preserved; assemblers depend on the same controller method references that controllers do today.
- No wire-shape change. Existing controller MockMvc tests pass without edits to expected JSON.
- Path opened (but not exercised) for a future account-collection endpoint to reuse `AccountModelAssembler` via `toCollectionModel(...)`.

**Non-Goals:**
- No pivot of the OpenAPI contract from typed `_links` objects to HAL's open `_links` map.
- No replacement of the generated `Link` DTO with Spring HATEOAS's own `org.springframework.hateoas.Link` on the wire.
- No new endpoints.
- No reshape of HAL content negotiation. The existing `application/hal+json` ↔ `application/json` behaviour stays.
- No move of any code out of the `infrastructure` module.

## Decisions

### Decision 1: Assemblers produce the populated **generated DTO**, not a hand-rolled `RepresentationModel` subclass

The cleanest Spring HATEOAS shape is `class AccountModel extends RepresentationModel<AccountModel> { ... }` plus a Jackson HAL serialiser that produces `_links` as a map. Adopting that here would force one of: (a) a parallel hand-rolled response type alongside the generated DTO, (b) deletion of the generated `_links` typed object from the OpenAPI document, or (c) bespoke Jackson configuration to suppress the generated `_links` field while emitting Spring HATEOAS's. All three pay a contract-first tax for an internal benefit.

Instead, each assembler is a plain Spring `@Component` exposing `toModel(...)` and `toCollectionModel(...)` methods. It does **not** `implements org.springframework.hateoas.server.RepresentationModelAssembler` — the interface bounds its output type to `D extends RepresentationModel<?>`, and the OpenAPI-generated DTOs are not subtypes (an earlier draft of this design claimed otherwise; that was wrong, surfaced at compile time, and is corrected here). We adopt the assembler **pattern** by structural shape rather than by marker interface. We sacrifice the textbook Spring HATEOAS shape but keep:
- Separation of concerns (the user's primary motivation): controller no longer knows what links exist.
- Reuse: a future collection endpoint injects the same assembler and calls `toCollectionModel(Iterable<Account>)`.
- The exact byte-for-byte wire output asserted by the existing tests.

`toCollectionModel(Iterable<Account>)` returns `List<AccountResponse>` (each populated via `toModel`) — sufficient until a real collection endpoint forces a richer shape.

**Alternatives considered:**
- *Hand-roll `AccountModel extends RepresentationModel<AccountModel>` and update the OpenAPI document to a HAL-map `_links` schema.* Rejected — significant contract change, ripples through `OpenApiContractTest`, and rewrites `hateoas-discovery` scenarios that today assert typed fields on `*Links`.
- *Use `EntityModel<AccountResponse>` wrappers.* Rejected — the generated `AccountResponse` already has a typed `_links` member; wrapping in `EntityModel` would yield two `_links` payloads (one populated, one empty) unless we configure Jackson to drop the inner. Net complexity without gain.

### Decision 2: Drop `LinkFactory`

Once both call sites move into assemblers, `LinkFactory` has exactly two methods and two callers. Each assembler can call `WebMvcLinkBuilder.linkTo(methodOn(...)).toUri().getPath()` directly and wrap the result in `new Link(href).templated(false)`. The 4-line helper does not earn a class; deleting it removes one indirection and one Spring-managed bean.

If, post-refactor, three or more assemblers need the same helper, reintroducing it is mechanical. Default to deleting.

**Alternative considered:** Keep `LinkFactory` as the lowest-level helper used by all assemblers. Rejected on the principle that two callers do not a shared utility make; revisit if a third assembler shows up.

### Decision 3: Assemblers live in the `infrastructure` module next to their controllers

The current package layout puts the index controller and link factory at `com.bank.core.infrastructure.web`, and the account controller at `com.bank.core.infrastructure.web.account`. Assemblers follow the same convention:
- `com.bank.core.infrastructure.web.IndexModelAssembler`
- `com.bank.core.infrastructure.web.account.AccountModelAssembler`

They are Spring `@Component` beans (constructor-injectable into controllers and tests). They depend only on generated DTOs and Spring HATEOAS — no domain-port dependency; the domain object is passed in by the controller.

### Decision 4: Assemblers do **not** read from `Accounts` or any application port

Controllers remain responsible for fetching the domain object from the application port, mapping `Optional.empty()` to `ResourceNotFoundException`, and then handing the loaded `Account` to the assembler. The assembler is a pure function of its input. This keeps testability simple (no port mocks needed for assembler unit tests) and preserves the existing exception-handling seam.

### Decision 5: The existing `AccountResponseMapper` continues to exist; the assembler composes it

`AccountController` currently uses an `AccountResponseMapper` to fill the non-links fields. The assembler can either (a) absorb the mapper or (b) delegate to it. We will (b) — delegate. The mapper carries no link logic and is already independently tested; keeping it intact narrows this change to "introduce assemblers" without bundling a mapper merge.

## Risks / Trade-offs

- **Risk:** Idiomatic Spring HATEOAS readers will expect `AccountModelAssembler` to return a `RepresentationModel` subtype and may be surprised by `RepresentationModelAssembler<Account, AccountResponse>` where the second parameter is a generated POJO. → Mitigation: a class-level Javadoc on each assembler explaining the contract-first tension (Decision 1) and pointing at this design document.
- **Risk:** Wire-shape regression — the move from `AccountController` building `AccountResponseLinks(self, transfers)` to the assembler building the same object could drop a field if the assembler is incomplete. → Mitigation: the existing `AccountLookupControllerTest` and `OpenApiContractTest` already assert the response body shape and the link `href` values. The refactor passes only if they pass.
- **Risk:** Removing `LinkFactory` deletes a Spring bean other code might depend on. → Mitigation: confirmed by grep that only `IndexController` and `AccountController` reference it; no `@Autowired LinkFactory` elsewhere in source or tests. Safe to remove in the same change after both controllers migrate.
- **Trade-off:** `toCollectionModel(Iterable<Account>)` returning `List<AccountResponse>` rather than a Spring HATEOAS `CollectionModel<AccountResponse>` foregoes auto-collection links. Acceptable for this change — no collection endpoint exists yet — and easy to upgrade later by changing only the assembler.

## Migration Plan

1. Add `AccountModelAssembler` and `IndexModelAssembler` alongside the existing controllers, both as Spring `@Component`s. Wire them into the controllers via constructor injection.
2. Move the link-building and DTO-population logic from each controller method into its assembler. Controllers now call `assembler.toModel(...)` (and `toCollectionModel(...)` for the assembler that supports it) and nothing else for response building.
3. Run the full test suite; expect green. The wire-shape assertions in `IndexControllerTest`, `AccountLookupControllerTest`, and `OpenApiContractTest` are the canary.
4. Delete `LinkFactory.java` and remove the `private final LinkFactory links` field plus its constructor parameter from both controllers.
5. Add focused unit tests for `AccountModelAssembler` and `IndexModelAssembler` that exercise the assembler in isolation (no `MockMvc`).
6. Update `openspec/specs/hateoas-discovery/spec.md` via this change's delta when archived.

**Rollback:** revert the change set; no schema, dependency, or contract changes to undo.

## Open Questions

- Should `AccountModelAssembler.toCollectionModel(Iterable<Account>)` be added speculatively, or wait for the first real collection endpoint? Current plan: add it minimally (return `List<AccountResponse>`) because it is a 6-line method and demonstrates the user's stated win; revisit signature when a collection endpoint actually lands.
- Future change worth flagging: pivoting the OpenAPI `_links` schema from typed objects to a HAL-style open map (`additionalProperties: $ref Link`). That would let assemblers return true `RepresentationModel` subtypes and unlock Spring HATEOAS's native HAL serialiser. Out of scope here; called out as a candidate follow-up.
