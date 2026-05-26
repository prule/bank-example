## Why

The `add-observability-prometheus-grafana` change shipped a Grafana dashboard, but every panel on it renders "No data" against an idle service — the dashboard's time ranges (1-minute and 5-minute rates) need sustained traffic to come alive. Hand-typed `curl` against `/api/v1/transfers` is enough to confirm one metric ticks but not enough to demonstrate the dashboard as designed: the rate panels stay flat, the outcome-breakdown legend shows a single line, and the operator walking through `ReadMe.md`'s Observability section hits a "looks broken" moment that is purely a "no traffic" moment.

We need a one-shot operator tool that takes the running service from "no traffic" to "every panel lit up", deterministically and quickly, so the dashboard demo matches the spec.

## What Changes

- Add `infrastructure/observability/generate-load.sh` — a `bash + curl` script (no extra runtime dependency) that the operator runs against a running bank-core instance and the running compose stack to populate every dashboard panel.
- The script issues a mixed stream of POST `/api/v1/transfers` requests at a configurable rate against the dev-seed accounts (`CUST-1001`, `CUST-1002`, `CUST-1003`, `CLEARING-000`):
  - ~70% **success** — small randomised amount from `CUST-1001` to `CUST-1002`.
  - ~15% **insufficient-funds** — over-large amount from `CUST-1003` (zero-balance) to `CUST-1001`.
  - ~15% **same-account-rejected** — `CUST-1001` → `CUST-1001` (rejected as `SameAccountTransferException`; intentionally NOT classified by `bank.transfer.executed{outcome=...}` per the metrics-exposure spec, but still exercises HTTP request rate, p95, and 400-error paths).
- Configurable via env vars with sensible defaults:
  - `BANK_URL` (default `http://localhost:8080`)
  - `RATE` — requests per second (default `5`)
  - `DURATION_SECONDS` — total runtime (default `120` — long enough for the 5-minute rate panels to populate while still finishing in a couple of minutes)
- Pre-flight: confirm the service is reachable (`GET /actuator/health` returns 200) and that the dev-seed accounts exist (`GET /api/v1/accounts/CUST-1001` returns 200). Fail-fast with a clear message if either is missing — guides the operator to start the app with `SPRING_PROFILES_ACTIVE=dev`.
- One-line summary at the end: total requests issued, count by HTTP status, runtime, effective rate.
- Add a short subsection to the `Observability` section of `ReadMe.md` pointing at the script with the canonical invocation.

Out of scope:
- **Lock-timeout outcomes** (`bank.transfer.executed{outcome="lock_timeout"}`) — provoking these deterministically requires either two concurrent transfers between the same pair with `bank.transfer.lock-wait-ms` lowered, or fault injection into the locker. Both add complexity disproportionate to a dashboard demo; the panel can stay flat.
- **Account-suspended outcomes** (`outcome="account_suspended"`) — there is no API to suspend an account; only the audit pipeline can. Skipped for the same reason.
- **Sustained / SLA load testing** — k6, JMeter, Gatling, etc. This is a demo tool, not a load-testing harness.
- **Distributed coordination** — single-process script issuing requests in series with a sleep budget per loop; no thread pools, no async, no rate-limiter library.

## Capabilities

### New Capabilities
- `load-generator`: Operator tooling that drives an HTTP-level synthetic transfer mix against a running bank-core instance to populate the observability dashboard. Owns the script location, pre-flight checks, request-mix ratios, configuration knobs, and operator workflow.

### Modified Capabilities
<!-- None. observability-stack stays as-is — the load gen is a separate operator action, not part of the compose stack. -->

## Impact

- **Code**: no Java change. One new shell script (`bash` + `curl`, ~80 lines including the comment block).
- **Ops directory**: `infrastructure/observability/generate-load.sh` (executable). Sits next to the compose stack as a sibling operator tool.
- **Docs**: `ReadMe.md` Observability section gains 3–4 lines for the canonical invocation and the env-var override list.
- **Tests**: a `bats`-style shell test would be over-engineering for a 60-line demo script. Instead, the implementation includes a `--dry-run` flag that prints the planned curls without issuing them, so the operator can sanity-check shape before firing live traffic, and the existing `DashboardCoverageTest` continues to gate the metric/panel contract regardless of how traffic is generated.
- **No impact on**: domain, application, infrastructure JVM code, OpenAPI contract, Flyway migrations, observability auto-config, or the compose stack itself.
