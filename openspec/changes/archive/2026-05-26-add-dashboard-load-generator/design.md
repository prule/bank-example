## Context

The Grafana dashboard shipped in the previous change has 11 panels. Most read 1-minute or 5-minute rates from counters and histograms. Against a fresh app with no traffic, every rate panel reads zero — visually identical to "the dashboard is broken". Manual `curl` works for confirming a single counter ticks but won't populate the rate windows or the per-outcome breakdown legend.

The repo already has a clean operator-tooling layout under `infrastructure/observability/` (compose file, prometheus config, grafana provisioning). The load generator slots in there as a sibling — same pattern as Prometheus and Grafana: pre-built tool, declarative config, operator runs it. The dev profile already seeds three customers (`CUST-1001/1002/1003`) and a clearing account, giving us a stable set of account numbers to drive against.

## Goals / Non-Goals

**Goals:**
- A single command (or two, including the compose-stack start) takes the dashboard from "all panels empty" to "every applicable panel lit up with non-trivial data".
- Zero new runtime dependencies. The script runs on any machine that has `bash` and `curl`.
- Deterministic outcome mix: the operator can predict that the success / insufficient-funds / 400-rejection panels will all show non-zero rates after a 2-minute run.
- Pre-flight failures point the operator at the right fix (start the app, set `SPRING_PROFILES_ACTIVE=dev`).
- The script terminates on its own at the configured duration. No background processes left behind.

**Non-Goals:**
- Load testing in the SLA sense. No latency assertions, no error-rate gating, no warmup-vs-measurement phases.
- Coverage of all four `bank.transfer.executed{outcome=...}` values. `lock_timeout` and `account_suspended` are intentionally not driven (see proposal "Out of scope").
- Concurrency. A single linear loop is enough to drive the 5 TPS default; threads add nothing here and complicate the dry-run output.
- A Gradle wrapper (`./gradlew :loadgen:run`). The compose stack isn't wrapped in Gradle either; the script should be discoverable next to it, not behind a Java task.

## Decisions

### Decision 1: Bash + curl, not Java / Gradle / Testcontainers

The script needs to drive 5–20 HTTP requests per second against a single endpoint, log outcomes, and stop. Bash + curl handles all of that in ~80 lines with no JVM startup tax and no transitive dependencies. The script lives next to the compose stack (which is also declarative-config + operator-runs-it) so the mental model is consistent.

**Alternatives considered:**
- *Java main + Gradle subproject.* Rejected — adds a module, a `build.gradle.kts`, JVM startup, and dependency surface for what's a `for` loop with curl.
- *k6 / Vegeta / wrk.* Rejected — adds an install step. We want "git clone, run the script". A real load-testing harness is a future concern, not part of demoing the dashboard.
- *Spring Boot CommandLineRunner under a `loadgen` profile.* Rejected — couples the test tool to the production app's deployment unit. Cleaner to keep it as a sibling operator artifact.

### Decision 2: Three outcome categories, fixed default ratios (70 / 15 / 15)

The dashboard has a "Transfer outcome rate" panel that breaks down by `outcome` tag. To see the legend show two distinct lines, we need at least two outcome values populated. `success` is trivial. The cheapest way to also populate `insufficient_funds` is a transfer from the zero-balance `CUST-1003` account.

The third category (same-account `CUST-1001` → `CUST-1001`) does NOT increment any `bank.transfer.executed{outcome=...}` series — by design, per the spec. But it DOES exercise HTTP rate panels, p95 latency, and the 400-response error path through `GlobalExceptionHandler`. That makes the HTTP panels visibly busier without inflating any classified metric inappropriately.

Why 70/15/15: enough success volume that the success-rate panel reads as the dominant signal (matching how a real system looks), enough rejection volume to make the per-outcome legend show two lines, enough total rejection volume that the HTTP 400 rate is plainly visible.

**Alternatives considered:**
- *Equal mix (33/33/33).* Rejected — visually misleading; real systems don't see 33% rejection.
- *Pure success.* Rejected — defeats the per-outcome breakdown the dashboard was built to surface.
- *Random uniform amounts.* Sufficient for shaping the distribution without engineering it — using `$RANDOM` in bash to pick a category each iteration.

### Decision 3: Pre-flight on health + seed-account presence

The most common operator failure mode is "I ran the script before starting the app" or "I forgot `SPRING_PROFILES_ACTIVE=dev`". Both are recoverable with a clear message:

- If `GET $BANK_URL/actuator/health` doesn't return 200, print "bank-core not reachable at $BANK_URL — start the app and try again" and exit non-zero.
- If `GET $BANK_URL/api/v1/accounts/CUST-1001` returns 404, print "dev seed data not found — start the app with SPRING_PROFILES_ACTIVE=dev so the F09 seed runs". Exit non-zero.

Both checks fire once at the top of the script, before any traffic. Zero ambiguity, zero "ran for 30 seconds then failed".

### Decision 4: `--dry-run` flag instead of unit tests

The script has no business logic worth unit-testing. The shape worth verifying is "the curl invocations it would issue". A `--dry-run` flag prints the body of each request that would be issued, with no network calls, so the operator (and any reviewer) can sanity-check the mix and the JSON shape before going live.

This is also the closest thing to a regression gate. If a future change to the script accidentally drops the `insufficient_funds` path, `--dry-run` makes the diff obvious; CI for shell scripts (bats / shellcheck) is out of scope.

### Decision 5: Rate control via `sleep`, not `xargs -P` or a token-bucket library

Default rate is 5 TPS, so sleep `0.2` between issues yields a roughly-right cadence. Drift over a 120s run is on the order of seconds — acceptable for a demo tool. Bash's built-in `printf -v` and a wall-clock check at the end yields the effective rate for the summary line.

For higher rates (e.g. `RATE=50`), single-process serial issuing might bottleneck on connection setup; the script accepts this limitation explicitly and documents it. Demo workload is 5–20 TPS; nobody using this for "real" load.

## Risks / Trade-offs

- **Risk:** The dev seed plan changes (account numbers renamed). → Mitigation: pre-flight checks for the specific account numbers fail loudly; updating the script is a two-line fix.
- **Risk:** Network/system delays cause actual rate to drift below configured. → Mitigation: summary line at the end shows effective rate; for a demo, ±20% is fine.
- **Risk:** Operator runs the script against a production-shaped instance and floods the real ledger. → Mitigation: pre-flight checks reject any account that isn't `CUST-1001/1002/1003`; the README warning explicitly calls out "local dev profile only". Hard to fully prevent without a flag in the API, which is too much for this change.
- **Trade-off:** No `lock_timeout` outcome on the dashboard during this run. Acceptable — the panel's existence documents the metric; demo doesn't have to drive every series.

## Migration Plan

1. Land the script + README pointer.
2. Manually verify against a `bootRun` instance: dashboard panels go from empty to populated within ~30 seconds of script start.

**Rollback:** delete the script and the README subsection. No code or schema impact.

## Open Questions

- Should the script emit Prometheus-format push gateway metrics about its own runtime (requests issued, average latency)? Currently no — the dashboard already shows the server-side view, which is what we care about. Revisit if the script ever needs to assert on its own performance.
