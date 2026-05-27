## Why

When spinning up the bank core system in local development, manual data entry or external scripts are required to create initial customer accounts and fund the clearing account. This change introduces an environment-gated, idempotent, and configuration-controlled startup data seeder to automatically seed a configured set of customer and clearing accounts, simplifying local verification and development testing.

## What Changes

- Add a low-precedence environment post-processor to register the alias `SEED_DATA` (e.g., `SEED_DATA=true`) to the core property `bank.seed.enabled`.
- Implement a conditional startup seeding component (`SeedDataRunner` executing `SeedData`) that is strictly active only when `bank.seed.enabled=true`.
- Support direct creation of the genesis clearing account aggregate in the database, while routing customer seed creations through the standard transactional `OpenAccountService` to reuse all duplicate guards and funding flows.
- Enforce idempotency on startup by checking if the clearing account is already present, safely returning a skipped report if so.
- Provide robust, loud failure propagation: if any customer seed fails, roll back that individual transaction, log a clear error, and abort the application startup to prevent silent corruption.

## Capabilities

### New Capabilities
- `dev-data-seeding`: Idempotent and environment-gated database seeding at startup for development and testing.

### Modified Capabilities
<!-- No requirement changes to existing capabilities. -->

## Impact

- **Affected Components**:
  - `:bootstrap` module: Will hold the seeding configuration, post-processor, beans, and runner.
  - `:application` module: We will define clean, decoupled ports/adapters (`OpensAccount` interface) if needed to decouple seeding from Spring.
- **APIs and Properties**:
  - Core properties introduced: `bank.seed.enabled`, `bank.seed.clearingAccountNumber`, `bank.seed.clearingAccountOpeningBalance`, and `bank.seed.customers[]`.
- **Startup Integrity**:
  - Failed seeding aborts startup, preventing invalid or incomplete environment configurations from running.
