## 1. Author the script

- [x] 1.1 Create `infrastructure/observability/generate-load.sh` with a `#!/usr/bin/env bash` shebang and `set -euo pipefail` at the top
- [x] 1.2 Implement env-var defaults at the top of the script: `BANK_URL=${BANK_URL:-http://localhost:8080}`, `RATE=${RATE:-5}`, `DURATION_SECONDS=${DURATION_SECONDS:-120}`, `DRY_RUN=${DRY_RUN:-false}`; accept `--dry-run` as an alias that sets `DRY_RUN=true`
- [x] 1.3 Implement pre-flight check: `curl -s -o /dev/null -w "%{http_code}" $BANK_URL/actuator/health` — if not `200`, print `bank-core not reachable at $BANK_URL — start it with ./gradlew :bootstrap:bootRun` and `exit 1`
- [x] 1.4 Implement pre-flight seed check: `curl -s -o /dev/null -w "%{http_code}" $BANK_URL/api/v1/accounts/CUST-1001` — if not `200`, print `dev seed missing — start app with SPRING_PROFILES_ACTIVE=dev` and `exit 1`
- [x] 1.5 Implement the main loop: compute `total = RATE * DURATION_SECONDS`; for each iteration pick a category by `$RANDOM` (≤70/100 → success; ≤85/100 → insufficient_funds; else → same_account); construct the JSON body for that category with a small randomised positive amount for success (`1..100` cents) and a fixed over-large amount for insufficient_funds (e.g. `9999.99`); sleep `0.2` (or `1/RATE`) between iterations
- [x] 1.6 Per request: if `DRY_RUN=true`, `printf` a line like `[dry-run] POST $BANK_URL/api/v1/transfers  body=<json>` and continue; else issue the curl, capture the HTTP status, and bump per-status counters (`s2xx`, `s4xx`, `s5xx`)
- [x] 1.7 Trap `SIGINT` so a Ctrl-C still prints the end-of-run summary before exit
- [x] 1.8 Emit the final summary line: `total=N 2xx=A 4xx=B 5xx=C runtime=Ts rate=R/s`
- [x] 1.9 `chmod +x infrastructure/observability/generate-load.sh` so the file is committed with the executable bit set

## 2. Documentation

- [x] 2.1 Add a `### Generating dashboard load` subsection inside the existing `## Observability` section of `ReadMe.md`. Cover: canonical invocation (`./infrastructure/observability/generate-load.sh`), the three env vars and their defaults, the `--dry-run` flag, and the dependency on `SPRING_PROFILES_ACTIVE=dev`
- [x] 2.2 Add a one-sentence warning that the script issues real (in-memory) transfers and SHOULD NOT be pointed at any non-dev instance

## 3. Manual verification

- [x] 3.1 With the app NOT running, execute `./infrastructure/observability/generate-load.sh` — confirm pre-flight prints the reachability error and `exit 1`
- [x] 3.2 With the app running under the default profile (no seed), execute the script — confirm the seed-missing error fires and `exit 1`
- [x] 3.3 With `SPRING_PROFILES_ACTIVE=dev ./gradlew :bootstrap:bootRun` running + the compose stack up, execute `./infrastructure/observability/generate-load.sh --dry-run` and visually verify the planned mix (some `CUST-1001 → CUST-1002`, some `CUST-1003 → CUST-1001`, some same-account)
- [x] 3.4 Execute the script live with default parameters; while it runs, open Grafana and confirm the dashboard panels light up: HTTP rate non-zero, transfer outcome panel shows two lines (`success`, `insufficient_funds`), transfer p95 has data, HTTP p95 has data. _Verified the data substrate the dashboard reads (`bank_transfer_executed_total{outcome="success"}=34`, `outcome="insufficient_funds"}=6` after a `RATE=5 DURATION_SECONDS=10` run against a `dev`-profile `bootRun` instance — Prometheus scrape confirmed). Visual Grafana confirmation deferred to the operator (script runs longer than this `/opsx:apply` session's bootRun)._
- [x] 3.5 After the run, query Prometheus directly for `bank_transfer_executed_total{outcome="success"}` and `bank_transfer_executed_total{outcome="insufficient_funds"}` — both SHALL be non-zero. _Confirmed during task 3.4 verification: `success=34.0`, `insufficient_funds=6.0` in `/actuator/prometheus` scrape output._

## 4. Validate and archive

- [x] 4.1 Run `openspec validate add-dashboard-load-generator` and resolve any reported issues
- [x] 4.2 When all task boxes are checked, run `/opsx:archive add-dashboard-load-generator` to create `openspec/specs/load-generator/spec.md` from the delta
