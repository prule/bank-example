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
