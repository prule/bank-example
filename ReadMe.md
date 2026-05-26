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

## Discovery

The running service is self-describing via HAL links. Start at the API root and follow `_links` from there — no need to hard-code URL templates.

```bash
curl http://localhost:8080/api/v1
```

Returns:

```json
{
  "_links": {
    "self":      { "href": "/api/v1",                                "templated": false },
    "accounts":  { "href": "/api/v1/accounts/{accountNumber}",       "templated": true  },
    "transfers": { "href": "/api/v1/transfers",                      "templated": false },
    "openapi":   { "href": "/v3/api-docs",                           "templated": false }
  }
}
```

Account responses carry their own `_links` (`self`, `transfers`) so a client holding an account can move money without consulting the contract again. HAL clients should send `Accept: application/hal+json`; naive clients get plain `application/json` with the same body.

## Observability

The running service exposes Prometheus-format metrics at `/actuator/prometheus`, alongside the standard `/actuator/health`, `/actuator/info`, and `/actuator/metrics` endpoints. A Docker Compose stack under `infrastructure/observability/` brings up Prometheus and Grafana pre-wired to scrape it and render a `Bank Core` dashboard.

Launch the stack (with the app already running on the host on port 8080):

```bash
docker compose -f infrastructure/observability/docker-compose.yaml up -d
```

- **Prometheus** — `http://localhost:9090` (`/targets` shows `bank-core` as `UP`).
- **Grafana** — `http://localhost:3000` (default first-login `admin` / `admin`; prompts to change). The `Bank Core` dashboard is pre-provisioned with panels for JVM, HTTP, transfer outcome, lock acquisition, journal verification, pending journals, and account suspension.

Tear down:

```bash
docker compose -f infrastructure/observability/docker-compose.yaml down
```

Custom metrics emitted by the app:

| Metric | Type | Tags |
| --- | --- | --- |
| `bank_transfer_executed_total` | counter | `outcome=success\|insufficient_funds\|account_suspended\|lock_timeout` |
| `bank_transfer_duration_seconds` | timer | — |
| `bank_lock_acquisition_seconds` | timer | `strategy=jvm\|db` |
| `bank_journal_verification_total` | counter | `outcome=verified\|failed` |
| `bank_journal_pending` | gauge | — |
| `bank_balance_drift_detected_total` | counter | — |
| `bank_account_suspended_total` | counter | `cause=drift\|journal_failure` |

All metric tags draw from closed enums — no account number, journal id, or other user-controlled value enters the tag set (cardinality is bounded). See `openspec/specs/metrics-exposure/spec.md` for the wire-shape requirements and `openspec/specs/observability-stack/spec.md` for the stack contract.

**Linux note**: Prometheus inside the compose network reaches the host app at `host.docker.internal`. On macOS and Windows this name resolves natively; on Linux the compose file's `extra_hosts: ["host.docker.internal:host-gateway"]` line provides the same mapping. No app-side change is needed.

The actuator endpoints are exposed without authentication — same posture as `/actuator/health` today. The study app runs on localhost; production-style hardening is out of scope.

### Generating dashboard load

Every dashboard panel reads from a rate window, so an idle service renders as "No data". `infrastructure/observability/generate-load.sh` is a `bash` + `curl` script that issues a mixed stream of transfers against the dev seed accounts (`CUST-1001/1002/1003`) and populates every applicable panel within ~30 seconds. Outcome mix is roughly 70% success, 15% insufficient-funds, 15% same-account (HTTP 400 — exercises rate panels without inflating any classified outcome counter).

```bash
# default: 5 req/s for 120s against http://localhost:8080
./infrastructure/observability/generate-load.sh

# preview without issuing live traffic
./infrastructure/observability/generate-load.sh --dry-run
```

Env-var overrides: `BANK_URL` (default `http://localhost:8080`), `RATE` (default `5`), `DURATION_SECONDS` (default `120`). The script requires the dev profile to be active — `SPRING_PROFILES_ACTIVE=dev ./gradlew :bootstrap:bootRun` — so the F09 seed creates the customer accounts it transfers between; the pre-flight check will refuse to issue traffic otherwise.

**Local dev only.** The script issues real (in-memory) transfers against whatever instance `BANK_URL` resolves to. Never point it at any non-dev instance.

## How to read this repo

If you want the business picture: `REQUIREMENTS.md`.
If you want the warts-and-all of one specific build: switch to that branch and read its source.
