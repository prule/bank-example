## Notes

* Create branch
* Install openspec 
  * `npm install -g @fission-ai/openspec@latest`
  * `openspec init`
* Copy specs into `specs` folder 
  * I used Claude code to generate specs and documentation from `v1-basic` codebase - lets see how well these specs do!
* Copy other docs into root
* Make sure Java 25 is available
  * `sdk install java 25.0.3-tem`
  * `sdk use java 25.0.3-tem`
  * `sdk env init`
* Update docs to use Java 25
* Update IntelliJ project to use Java 25
* The specs aren't written to OpenSpec standard so will need to be converted as they are done.
  * Should have instructed Claude to create the specs in OpenSpec format.
* propose/apply/archive F00-project-setup
  * Had trouble using Gradle since 8.12 was installed and wasn't working with Java 25. It used 21 to run Gradle.
    * It would have been better if I'd upgraded Gradle beforehand.
* Should have said to always use latest versions of libraries - it didn't always do that because of the requirements (spring boot, archunit, gradle) - should check requirements don't specify versions and just refer to latest.
* Asked Claude to convert all the specs to openspec format now
  * Can we convert all the specs to openspec format now?
    * Move existing to openspec/specs/legacy/
    * Direct doc refactor (Recommended)
* propose/apply/archive contract-first-api
* propose/apply/archive api-error-contract
* propose/apply/archive account-domain
* propose/apply/archive immutable-ledger
* Interesting that `transfer-locking` didn't have db tables at that time so implemented locking without db. Perhaps should break down differently to get db set up earlier.





---

## Sample propose output

```text
/opsx:propose dev-data-seeding
```

Let me look at existing project structure and any seeding-related code.

Now I have enough context. Let me write the proposal.

Spec valid. Now writing tasks.

All four artifacts created and validated.

## Change `dev-data-seeding` ready

Location: [openspec/changes/dev-data-seeding/](openspec/changes/dev-data-seeding/)

- [proposal.md](openspec/changes/dev-data-seeding/proposal.md) — F09 implements the existing `dev-data-seeding` capability spec: env-gated `SeedDataRunner` that creates the clearing account directly then opens customer accounts through F08's transactional pipeline, with `SEED_DATA` env-var alias, `@ConditionalOnProperty` gating, and dev-profile auto-enable.
- [design.md](openspec/changes/dev-data-seeding/design.md) — eight decisions: `ApplicationRunner` over `@PostConstruct`; gate every seed bean; per-customer atomicity (not whole-plan); `bank.seed.enabled` + `SEED_DATA` alias via `EnvironmentPostProcessor`; introduce `OpensAccount` functional interface to keep the application module Spring-free; idempotency via clearing-account precondition; dev defaults in `application-dev.yaml`; clearing account created via `Account.open + accounts.save` (the legitimate exception to the "every credit has a ledger entry" rule).
- [specs/dev-data-seeding/spec.md](openspec/changes/dev-data-seeding/specs/dev-data-seeding/spec.md) — sharpens the existing four high-level requirements into implementation-precise scenarios (bean conditions, log strings, property names, partial-state semantics).
- [tasks.md](openspec/changes/dev-data-seeding/tasks.md) — 13 task groups: value records, `OpensAccount` adapter, `SeedData` use case, `SeedProperties` DTO, `SeedDataRunner`, `SEED_DATA` `EnvironmentPostProcessor`, bootstrap wiring, YAML config, three integration tests (on/off/failure), ArchUnit, and a manual smoke-test pass.

Run `/opsx:apply dev-data-seeding` to start implementing.

---
