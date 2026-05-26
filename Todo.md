Brainstorm — ranked by tutorial value vs scope:

## Tier A: high signal, narrow scope

- **Idempotency on `POST /api/v1/transfers`** — `Idempotency-Key` header, small seen-keys table, replay returns the original 204. Classic financial-API concern. Touches contract, controller, persistence. ~1 day's work.
- **Dockerfile + add app to compose stack** — right now operator must run `bootRun` on host AND `docker compose up` on the observability stack. One Dockerfile + a service in `infrastructure/observability/docker-compose.yaml` makes the demo `docker compose up` and done. `host.docker.internal` caveat goes away.
- **Prometheus alerting rules** — already called out as deferred in the observability design. Add `infrastructure/observability/prometheus/alerts.yml` with rules for "suspended accounts spike", "pending journals stuck", "drift detected this hour". Doesn't need Alertmanager — Grafana can render alert state.
- **Structured JSON logging** — `logback-spring.xml` with `LogstashEncoder`. Every log line becomes parseable. Pairs with the observability story.

## Tier B: shows breadth

- **API auth** — Spring Security with a single static API key or basic auth. Demonstrates the auth boundary, exercises the GlobalExceptionHandler for 401/403, lets you talk about `permitAll` on `/actuator/health`.
- **Pagination + account statement endpoint** — `GET /api/v1/accounts/{n}/statement?cursor=&limit=` returning paginated ledger movements. Read side is one endpoint today; this gives HATEOAS something interesting to link to (`next`, `prev`).
- **Property-based tests** — jqwik for `Money`, `AccountNumber`, the lock-canonical-order rule. Value-object invariants are perfect prop-test material.
- **Verification scheduler exception path test** — there's a unit test gap around what happens when `JournalEntries.findByStatus` throws mid-tick.

## Tier C: bigger scope, meaningful payoff

- **Distributed tracing** — explicit non-goal in the observability change, but worth picking up next. OpenTelemetry SDK + Tempo container in the compose stack + a "Traces" link on the dashboard panels. Two-day job.
- **PostgreSQL profile** — `application-postgres.yaml`, Testcontainers for the integration tests, Flyway already does the dialect switch via `lock_timeout` placeholder. Demonstrates real-DB story; H2 with `MODE=PostgreSQL` is half-there.
- **Multi-currency `Money`** — single biggest domain enrichment. Pulls in FX rates, currency mismatches at transfer boundary, settlement vs presentation currencies. Bigger ledger schema change.

## Process/quality

- **Mutation testing (PIT)** — runs against domain + application modules. Surfaces dead test code.
- **ArchUnit coverage report** — visualise which boundaries are enforced.
- **`CONTRIBUTING.md` + branch-comparison guide** — README mentions branches as the study unit but doesn't say how to read them side by side.

## My picks if you want one next

If you want **one** next change that pays best for a study repo: **Dockerfile + app-in-compose**. Removes the "two-terminal dance" from the README runbook, makes every later demo one-command, and shows the operator story end-to-end. Idempotency is a close second — most "wait, banks need this" moment for a reader.

Either lights up the existing infrastructure rather than adding new surface area. Both ~half-day to a day.