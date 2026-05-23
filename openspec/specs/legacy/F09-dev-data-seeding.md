# F09 — Dev Data Seeding

## Summary

On startup, optionally bootstrap the database with a small, known set of accounts so that developers and tests have something realistic to hit immediately. Seeding is gated, idempotent, and never runs in production by default.

## User story

As a developer, I want the service to come up with a pre-funded clearing account and a couple of customer accounts already created, so that I can hit the API, run integration tests, and demo the system without first having to set up data by hand.

## In scope

- The trigger for seeding.
- The seed data set.
- Idempotency: seeding never duplicates data if it has already run.
- The single special-case "direct" creation of the clearing account.

## Out of scope

- Seeding in a production environment (seeding is a dev/test affordance only).
- Loading large datasets, performance fixtures, or fuzz inputs.
- Reset / teardown of seeded data (handled by `ddl-auto: create-drop` in dev).

## Functional requirements

- Seeding runs once on application startup, and only when an explicit configuration switch is enabled. The switch must be controllable from the environment (e.g. `SEED_DATA=true`) without code changes.
- When the switch is OFF, the seeding component does not run at all and produces no log noise.
- When the switch is ON but the database already contains accounts, seeding skips itself and logs that it did so. Seeding never duplicates accounts.
- When the switch is ON and the database is empty, seeding executes the following bootstrap, in order:
  1. Create the **clearing account** directly with a configured opening balance (this is the only legitimate place in the system where an account is created with a non-zero balance without a funding transfer, because no clearing account yet exists to fund it).
  2. Create one or more customer accounts via the F08 account-opening path, which funds them by transfer from the clearing account.
- The seed account numbers and balances must be configuration-driven for the customer accounts; the clearing account number is a fixed system constant.
- Seeding failures are loud — partial seeding must not leave the system half-set-up.

## Acceptance criteria

1. With the seed switch OFF, starting against an empty database leaves the database empty.
2. With the seed switch ON, starting against an empty database produces: a clearing account with the configured balance, and the configured set of customer accounts with their configured opening balances, each funded by a journal entry from the clearing account.
3. The total of customer opening balances equals the reduction in the clearing account's balance.
4. Starting the service again with the seed switch ON (database now populated) does NOT add another clearing account, does NOT add duplicate customers, and emits a log line indicating seeding was skipped.
5. If the seed process fails partway, the visible state is consistent: either all seeded accounts exist or none do.
6. The seed switch is read from an environment variable, not hardcoded.

## Dependencies

- F08 (Account opening) — customer accounts are created through the opening flow, not by direct insertion.

## Open questions

- The configured seed balances are currently literals in code. **Decision needed:** move to externalised configuration before any non-dev usage.
- Should seeding ever run in production by accident? Recommend an explicit profile guard so that even a misconfigured `SEED_DATA=true` in a production profile is refused.
