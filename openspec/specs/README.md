# Bank Core — Feature Specs

Per-feature specs for spec-driven development. Each spec is independently buildable and independently testable. Higher-level context in `../REQUIREMENTS.md`; implementation-level detail in `../SPECIFICATION.md`.

## How to use

- Pick a spec. Build only what it describes. Don't sneak in scope from neighbours.
- The **Acceptance Criteria** section of each spec is the contract — every bullet must be demonstrable by an automated test or a documented manual check before the feature is considered done.
- **Dependencies** tell you which specs must be in place before this one can be implemented. Foundations (F01–F04) ship first.

## Spec index

### Foundations
| ID | Feature | Summary |
|----|---------|---------|
| [F00](F00-project-setup.md) | Project setup | Build, profiles, persistence wiring, package layout |
| [F01](F01-account-domain.md) | Account domain rules | Lifecycle, status, debit/credit invariants |
| [F02](F02-immutable-ledger.md) | Immutable ledger | Journal entries and ledger movements |
| [F03](F03-api-error-contract.md) | API error contract | Stable error codes, shape, HTTP mapping |
| [F04](F04-contract-first-api.md) | Contract-first API | OpenAPI as source of truth, generated stubs |

### Capabilities
| ID | Feature | Summary | Depends on |
|----|---------|---------|------------|
| [F05](F05-account-lookup.md) | Account lookup API | Read an account by number | F01, F03, F04 |
| [F06](F06-fund-transfer.md) | Fund transfer API | Move money atomically between two accounts | F01, F02, F03, F04, F07 |
| [F07](F07-deadlock-free-locking.md) | Deadlock-free concurrent transfers | Canonical lock ordering, contention safety | F01 |
| [F08](F08-account-opening.md) | Account opening | Create funded customer accounts via clearing | F01, F02, F06 |
| [F09](F09-dev-data-seeding.md) | Dev data seeding | Bootstrap dev environment on startup | F08 |

### Continuous audit
| ID | Feature | Summary | Depends on |
|----|---------|---------|------------|
| [F10](F10-journal-verification-sweep.md) | Pending journal reconciliation | Verify or fail every pending journal | F02 |
| [F11](F11-balance-drift-detection.md) | Balance drift detection | Suspend accounts where balance ≠ ledger | F01, F02, F06 |

## Suggested implementation order

1. F00 (project skeleton — nothing else can start without it).
2. F04 (contract-first API plumbing) and F03 (error contract) in parallel.
3. F01, F02 (domain foundations) in parallel.
4. F07 (locking primitives).
5. F05, F06 (the two API endpoints).
6. F08, F09 (opening + seeding) — F09 verifies the rest in dev.
7. F10, F11 (background audit). Ship F10 before F11.

## Spec template

Each spec follows the same shape:

- **Summary** — one paragraph.
- **User story** — "As a … I want … so that …".
- **In scope / Out of scope** — explicit boundaries.
- **Functional requirements** — what the feature does.
- **Acceptance criteria** — testable bullets, the contract.
- **Dependencies** — other specs that must ship first.
- **Open questions** — flagged items needing a decision before/during build.
