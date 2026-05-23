# Bank Core

A study project: a small double-entry bank ledger built and rebuilt under different conditions, so that the same problem can be compared across AI tools, prompting styles, and engineering disciplines.

The problem itself is intentionally narrow — move money safely between accounts and keep an audit trail — so the interesting variable is *how* each version was produced and how the resulting code reads, not what it does.

## Core concepts

These five ideas are the spine of the project. Every version, regardless of how it was generated, must honour them. They are also what makes the exercise non-trivial — each one carries a real engineering trade-off rather than a one-line implementation.

### Contract-first API (OpenAPI)

The public HTTP surface is defined in an OpenAPI document checked into the repo. Server interfaces and request/response DTOs are generated from that contract during the build. Hand-written controllers implement the generated interfaces; nothing in the network layer is invented in code first and then back-documented. The contract is the source of truth — if the YAML and the code disagree, the YAML wins.

### Rich domain model

Business rules live on the entities themselves, not in a swarm of service helpers. An `Account` knows how to debit and credit itself, refuses non-positive amounts, refuses to transact when not Active. There are no public setters. State changes happen through named methods (`debit`, `credit`, `suspend`, `reactivate`) so it is impossible to reach an illegal state by mistake — the domain rejects you before the database does.

### Deadlock-free pessimistic locking

Transfers acquire row-level write locks on both accounts before mutating them. To prevent two concurrent transfers between the same pair of accounts (one in each direction) from deadlocking, the locks are always acquired in a canonical order derived from the account numbers themselves, not from the order the caller passed them. The lower account number locks first. Under heavy contention, transfers queue up instead of cycling into a deadlock.

### Journal ledger

Every successful transfer produces an immutable accounting record: a journal entry with one debit movement and one credit movement of equal amount. Ledger rows are never updated or deleted — corrections happen only by writing new compensating entries (out of scope here, but the model does not foreclose it). The cached `balance` on the account is a convenience snapshot; the ledger is the truth.

### Background auditing

Two continuous sweepers police the system at runtime as defence-in-depth.

A **journal reconciler** picks up Pending journals and checks that their movements sum to zero — using a single database-side aggregate, not a hydrate-and-loop in JVM memory. Balanced journals are promoted to Verified; unbalanced journals are marked Failed and every account they touched is automatically Suspended.

A **balance drift detector** compares each account's cached balance against the sum of its ledger movements. Any account where the two disagree is Suspended pending investigation. The audit uses a persistent checkpoint over the ledger's monotonic id, with a captured ceiling per tick, so that it never reprocesses verified history and never misses new history — including across restarts.

The system never tries to silently "fix" a discrepancy. When in doubt, the account is taken out of circulation.

## Versions

The same problem is rebuilt across git branches. Each branch is a snapshot of one approach.

### `v1-basic` — Gemini in the browser

The original implementation, written by prompting Google Gemini in a web browser and pasting the results back. No skills, no harness, no extended context — just the model's default style (I have made a couple of manual stylistic changes).

The output is a working Spring Boot service that covers all five core concepts above. The code reads like a textbook tutorial in places: chatty comments, repeated patterns, scattered logic across services, scheduled jobs that mix orchestration with business decisions. It works and the tests pass. It is also a useful baseline for what an unguided web-browser AI session looks like.

### `v2-sdd` — Spec-driven from generated specs

Starting from `v1-basic`, Claude was used to derive specification documents:

- `REQUIREMENTS.md` — high-level business requirements.
- `SPECIFICATION.md` — implementation-level technical spec.
- `specs/F01..F11` — one focused spec per feature, each with explicit acceptance criteria.

The `v2-sdd` branch is the rebuild produced *from those specs only*, with no peeking at the v1 source. The point is to see whether spec-driven development with an AI agent — given precise, testable inputs — produces code that is closer to what a careful engineer would write, and to compare the resulting style and structure against the unguided v1.

### Future branches

Likely candidates:

- `v3-clean` — same specs, deliberately refactored toward the style described below.
- Possibly a port to a different stack to test how much of the design survives.
- Maybe a Kotlin comparison
- Maybe a hand coded comparison

The branches are meant to be *read side by side*, not merged. The README of each branch will name what changed and why.

## Style direction for v2 onwards

Specific refactoring intentions for the rebuilds, called out so they don't get lost in commit noise.

**Single-responsibility classes, smaller names.**
Replace umbrella service classes (e.g. `LedgerAuditorService` does verification *and* containment *and* suspension) with two or three small components, one job each. Class names should describe what the class is, not what it does to the world (`JournalVerifier`, not `LedgerAuditorService`).

**Command and result objects.**
Service methods take a single command (`TransferFundsCommand`) and return a typed result (`TransferResult`) instead of long argument lists and bare void/throws. Inputs are validated at the command boundary; outputs carry enough information for the caller to decide what to render.

**Separate orchestration from business logic.**
The schedulers (`LedgerReconciliationScheduler`, `BalanceDriftDetectorScheduler`) should be thin Spring shells whose only job is "wake up, call the use case, log the result", in exactly the same way a REST controller is a thin shell over a use case. The actual decision logic — what to do with a drifted account, how to verify a journal — lives in plain, framework-free classes that are unit-testable without a Spring context.

**Boundary discipline / clean architecture.**
Domain types do not import Spring or Hibernate annotations. Persistence types are separate from domain types. Generated DTOs sit at the network boundary; nothing inside the domain references them. The dependency arrows all point inward toward the domain.

**HATEOAS-style links in responses.**
Account and transfer responses include navigable links (`self`, `transfers`, `journal`) so that clients can discover the next legal action rather than hard-coding URL templates. Useful for the lookup endpoint in particular — a returned account should advertise where to read its history or initiate a transfer.

**Renames.**
A handful of names in v1 read as ChatGPT-default-tone marketing prose ("EMERGENCY CONTAINMENT", "SECURITY BREACH ALARM", "CRITICAL INVARIANT VIOLATION"). These go. Logs are factual; class names are nouns; method names are verbs.

## Repository layout

```
.
├── INTRODUCTION.md           — this file
├── REQUIREMENTS.md           — business requirements (audience: stakeholders)
├── SPECIFICATION.md          — full technical spec (audience: rebuild engineer)
├── specs/                    — per-feature specs for spec-driven dev
│   ├── README.md
│   └── F01..F11-*.md
├── src/                      — current branch's implementation
├── sql/                      — historical schema snapshots (NOT migrations)
└── build.gradle.kts
```

The three top-level docs and `specs/` are stable across branches. `src/` and `build.gradle.kts` differ per branch.

## How to read this repo

If you want the business picture: `REQUIREMENTS.md`.
If you want to rebuild it: `specs/README.md` and work through F01–F11.
If you want the warts-and-all of one specific build: switch to that branch and read its source.
