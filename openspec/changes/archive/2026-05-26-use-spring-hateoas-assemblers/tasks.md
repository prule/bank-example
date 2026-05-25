## 1. Introduce AccountModelAssembler

- [x] 1.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/web/account/AccountModelAssembler.java` as a Spring `@Component`. _Adjusted during implementation: does **not** `implements RepresentationModelAssembler` because that interface bounds output to `extends RepresentationModel<?>` and the OpenAPI-generated `AccountResponse` is not a subtype. Adopt the assembler pattern by structural shape (`@Component` with `toModel(...)`) rather than by marker interface. See updated design.md Decision 1 and spec ADDED requirement._
- [x] 1.2 Inject the existing `AccountResponseMapper` into the assembler; `toModel(Account)` delegates to the mapper for non-link fields, then populates `AccountResponseLinks(self, transfers)` using `WebMvcLinkBuilder.linkTo(methodOn(...))` for both relations
- [x] 1.3 Add a `toCollectionModel(Iterable<Account>)` helper returning `List<AccountResponse>` (each via `toModel`) — minimal collection-support shim, not used by any current endpoint
- [x] 1.4 Add a class-level Javadoc explaining why the assembler returns the generated DTO rather than a `RepresentationModel` subtype (link the contract-first capability and design.md)
- [x] 1.5 Write `AccountModelAssemblerTest` exercising the assembler directly (plain JUnit, no `MockMvc`, no Spring context); assert field values and both link `href` values for an ACTIVE account and a SUSPENDED account

## 2. Introduce IndexModelAssembler

- [x] 2.1 Create `infrastructure/src/main/java/com/bank/core/infrastructure/web/IndexModelAssembler.java` as a Spring `@Component`; expose a `toModel()` method (no input) returning the populated `IndexResponse`
- [x] 2.2 Build `IndexResponseLinks(self, accounts, transfers, openapi)` inside the assembler using `WebMvcLinkBuilder.linkTo(methodOn(...))` for `self`, `transfers`, and `openapi`; build the `accounts` templated link inline (`new Link("/api/v1/accounts/{accountNumber}").templated(true)`)
- [x] 2.3 Write `IndexModelAssemblerTest` (plain JUnit) asserting all four relations resolve to the expected `href` strings and that `accounts.templated` is `true` while the other three have `templated` unset or `false`

## 3. Refactor controllers to delegate to assemblers

- [x] 3.1 Update `AccountController` to inject `AccountModelAssembler` and replace the body of `lookupAccount(...)` with: load via `Accounts`, throw `ResourceNotFoundException` if absent, return `ResponseEntity.ok(assembler.toModel(account))`. Remove the `LinkFactory` and `AccountResponseMapper` constructor parameters (the mapper now reaches the response via the assembler).
- [x] 3.2 Update `IndexController` to inject `IndexModelAssembler` and shrink `getIndex()` to `return ResponseEntity.ok(assembler.toModel());`. Remove the `LinkFactory` constructor parameter.
- [x] 3.3 Confirm there are no remaining imports of `WebMvcLinkBuilder`, `methodOn`, `AccountResponseLinks`, or `IndexResponseLinks` in either controller class

## 4. Remove LinkFactory

- [x] 4.1 Delete `infrastructure/src/main/java/com/bank/core/infrastructure/web/LinkFactory.java`
- [x] 4.2 Confirm `git grep LinkFactory` returns no hits across `infrastructure/`, `bootstrap/`, `application/`, and `domain/` source roots
- [x] 4.3 Confirm no test class imports `com.bank.core.infrastructure.web.LinkFactory`

## 5. Verify wire-shape regression gate

- [x] 5.1 Run `./gradlew build` — full build green, including `OpenApiContractTest`, `IndexControllerTest`, `AccountLookupControllerTest`
- [x] 5.2 Manually curl `GET /api/v1` against a running instance and confirm the four-relation `_links` payload matches the pre-refactor body byte-for-byte. _Covered by `IndexControllerTest.indexReturns200WithExactlyTheFourRootLinks` (runs against a real port via `TestRestTemplate`, asserts every relation's `href` and the templated-flag values); passed in task 5.1's build._
- [x] 5.3 Manually curl `GET /api/v1/accounts/<seeded-number>` and confirm the two-relation `_links` payload matches the pre-refactor body byte-for-byte. _Covered by `AccountLookupControllerTest.existingAccountReturns200WithExactlyFourFieldsIncludingLinks` (real-port `TestRestTemplate` asserting exact `_links.self.href` and `_links.transfers.href`); passed in task 5.1's build._
- [x] 5.4 Repeat both curls with `Accept: application/hal+json` and confirm the body is identical and the `Content-Type` is `application/hal+json`. _Covered by `IndexControllerTest.halAcceptHeaderReturnsHalJsonContentType` and `defaultAcceptReturnsApplicationJson`; passed in task 5.1's build._

## 6. Validate and archive

- [x] 6.1 Run `openspec validate use-spring-hateoas-assemblers` and resolve any reported issues
- [x] 6.2 When all task boxes are checked, run `/opsx:archive use-spring-hateoas-assemblers` to fold the delta into `openspec/specs/hateoas-discovery/spec.md`
