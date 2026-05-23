# Bank Core — Capability Specs

Per-capability specs in OpenSpec format. Each spec is independently buildable and independently testable. Higher-level context lives in `../../REQUIREMENTS.md`; implementation-level detail lives in `../../SPECIFICATION.md`.

Earlier per-feature drafts (`F00`–`F11`) are preserved under [`legacy/`](legacy/) for reference; the OpenSpec-format specs in this directory are the source of truth going forward.

## How to use

- Pick a capability. Build only what its `## Requirements` describes.
- Each `### Requirement:` is normative (SHALL/MUST). Each `#### Scenario:` is a testable bullet — every scenario should be demonstrable by an automated test or a documented manual check before the requirement is considered satisfied.
- Cross-references between capabilities use `[[capability-name]]` links — follow these to see what dependencies a capability implies.
- Validate any spec with `openspec validate --specs <name>`. Validate all with `openspec validate --specs`.

## Capability index

### Foundations
| Capability | Summary |
|------------|---------|
| [project-setup](project-setup/spec.md) | Multi-module Gradle build, profiles, Flyway, Actuator, ArchUnit boundary tests |
| [account-domain](account-domain/spec.md) | Account lifecycle, status, debit/credit invariants |
| [immutable-ledger](immutable-ledger/spec.md) | Journal entries and ledger movements |
| [api-error-contract](api-error-contract/spec.md) | Stable error codes, envelope shape, HTTP mapping |
| [contract-first-api](contract-first-api/spec.md) | OpenAPI as source of truth, generated stubs |

### Capabilities
| Capability | Summary | Depends on |
|------------|---------|------------|
| [account-lookup](account-lookup/spec.md) | `GET /api/v1/accounts/{accountNumber}` | account-domain, api-error-contract, contract-first-api |
| [fund-transfer](fund-transfer/spec.md) | `POST /api/v1/transfers` — atomic move with one balanced journal | account-domain, immutable-ledger, api-error-contract, contract-first-api, transfer-locking |
| [transfer-locking](transfer-locking/spec.md) | Canonical lock ordering, contention safety | account-domain |
| [account-opening](account-opening/spec.md) | Create + fund customer accounts via clearing | account-domain, immutable-ledger, fund-transfer |
| [dev-data-seeding](dev-data-seeding/spec.md) | Env-gated startup seed | account-opening |

### Continuous audit
| Capability | Summary | Depends on |
|------------|---------|------------|
| [journal-verification](journal-verification/spec.md) | Periodic balance check on Pending journals | immutable-ledger, account-domain |
| [balance-drift-detection](balance-drift-detection/spec.md) | Cached-balance vs ledger reconciliation with persistent cursor | account-domain, immutable-ledger, fund-transfer |

## Suggested implementation order

1. `project-setup` (nothing else can start without it).
2. `contract-first-api` and `api-error-contract` in parallel.
3. `account-domain`, `immutable-ledger` in parallel.
4. `transfer-locking`.
5. `account-lookup`, `fund-transfer`.
6. `account-opening`, `dev-data-seeding` — seeding verifies the rest in dev.
7. `journal-verification`, then `balance-drift-detection`.

## Spec format

Each spec follows the OpenSpec capability shape:

```markdown
# <Title>

## Purpose

<one paragraph: what this capability owns, what it does not>

## Requirements

### Requirement: <name>
<normative sentence using SHALL/MUST>

#### Scenario: <name>
- **WHEN** <observable condition>
- **THEN** <expected outcome>
```

Every requirement has at least one scenario. Scenarios use exactly four hashtags (`####`) — three hashtags or bullets will fail validation silently.
