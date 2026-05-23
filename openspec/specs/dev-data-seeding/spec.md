# Dev Data Seeding

## Purpose

On startup, optionally bootstrap the database with a small known set of accounts so developers and tests have realistic data to hit immediately. Seeding is environment-gated, idempotent, and never runs in production by default.

## Requirements

### Requirement: Seeding is gated by an environment-controlled switch

Seeding SHALL run only when an explicit configuration switch is enabled, controllable from the environment (e.g. `SEED_DATA=true`) without code changes. When the switch is OFF, the seeding component SHALL NOT run and SHALL produce no log noise.

#### Scenario: Switch OFF leaves an empty database empty
- **WHEN** the service starts against an empty database with the seed switch OFF
- **THEN** no accounts are created and the seeding component emits no log lines

#### Scenario: Switch read from environment
- **WHEN** the seed switch is set via environment variable
- **THEN** the value takes effect without code changes; the switch is NOT hard-coded in Java

### Requirement: Bootstrap creates clearing account directly, customers via opening

When the switch is ON and the database is empty, seeding SHALL execute in order:
1. Create the clearing account directly with a configured opening balance — this is the ONLY legitimate place in the system where an account is created with a non-zero balance without a funding transfer, because no clearing account yet exists to fund it.
2. Create one or more customer accounts via [[account-opening]], which funds them by transfer from the clearing account.

The clearing account number SHALL be a fixed system constant. Customer account numbers and balances SHALL be configuration-driven.

#### Scenario: Empty DB with switch ON produces clearing + funded customers
- **WHEN** the service starts against an empty database with the seed switch ON
- **THEN** a clearing account exists with the configured opening balance, the configured set of customer accounts exist each with their configured opening balance, and each customer account was funded by a journal entry from the clearing account (per [[account-opening]])

#### Scenario: Sum of customer fundings reduces clearing balance exactly
- **WHEN** seeding completes
- **THEN** the clearing account's balance has been reduced by exactly the sum of all customer opening balances

### Requirement: Idempotent across restarts

When the switch is ON but the database already contains accounts, seeding SHALL skip itself, emit a single log line indicating the skip, and SHALL NOT duplicate any account.

#### Scenario: Second start with switch ON does not duplicate
- **WHEN** the service starts a second time with the seed switch ON, against a database already populated by an earlier seed run
- **THEN** no new clearing account, no new customer accounts, no new journal entries are created, and a log line indicates that seeding was skipped

### Requirement: Loud failure, no half-set-up state

Seed failures SHALL be loud and SHALL NOT leave the system half-set-up. Either all seeded accounts exist or none do.

#### Scenario: Mid-seed failure leaves no partial state
- **WHEN** seeding fails partway through (e.g. an account-opening call rejects)
- **THEN** the visible database state is consistent: either every seeded account exists or none do, and a high-severity log line describes the failure
