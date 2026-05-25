## Context

Multi-module Gradle project. Manifests Renovate must watch:

- `build.gradle.kts` (root + 4 subprojects: `domain`, `application`, `infrastructure`, `bootstrap`) — Spring Boot 3.4.5 BOM, `io.spring.dependency-management` 1.1.7, Java 25 toolchain
- `gradle/wrapper/gradle-wrapper.properties` — currently Gradle 8.14.5
- `.sdkmanrc` — Temurin Java 25.0.3-tem
- `.github/workflows/build.yml` (introduced by [add-ci-build-test](../../changes/archive/2026-05-25-add-ci-build-test/)) — `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`

No `gradle/libs.versions.toml` yet, so dependencies are declared inline in each `build.gradle.kts`. Renovate's `gradle` manager handles inline coordinates fine.

CI gate from `ci-build` capability runs on every PR — Renovate PRs ride that same workflow with no extra config.

## Goals / Non-Goals

**Goals:**
- Single source of truth for update policy (`renovate.json`)
- Group related ecosystem updates into one PR each to minimize review overhead
- Keep risky upgrades (major version bumps, Mockito/ByteBuddy under JDK 25) as solo PRs requiring manual review
- Weekday-only, off-hours scheduling so PR notifications don't flood working hours
- Cap concurrent PRs to keep CI queue and reviewer load bounded

**Non-Goals:**
- Vendoring or self-hosting Renovate — assume Renovate GitHub App
- Mend.io / Snyk / Dependabot setup
- Auto-merging anything beyond GHA patch bumps
- Migrating to a `libs.versions.toml` catalog (worth doing later but out of scope here)
- Lockfile generation (`./gradlew dependencies --write-locks`) — out of scope

## Decisions

**Config location: `renovate.json` at repo root**
Most common, picked up by Renovate without any path overrides. Alternative: `.github/renovate.json5` (allows comments) — rejected for now, keep it conventional; comments live in this design doc and the proposal.

**Preset stack: `config:recommended` + `:dependencyDashboard` + `:semanticCommits`**
- `config:recommended` is the current Renovate default (replaces deprecated `config:base`). Sensible separateMajorMinor, prHourlyLimit, etc.
- `:dependencyDashboard` opens a tracking issue so we can see queued updates at a glance.
- `:semanticCommits` makes PR titles `chore(deps): ...` — keeps `git log` clean.

**packageRules grouping:**

| Group | Match | Rationale |
|-------|-------|-----------|
| Spring Boot | `matchPackagePrefixes: ["org.springframework.boot", "org.springframework.cloud"]` + `groupName: "spring-boot"` | Boot upgrade touches BOM + dependency-management; one PR keeps versions coherent |
| GitHub Actions | `matchManagers: ["github-actions"]` + `groupName: "github-actions"` + `automerge: true` (patch + minor) | Action versions almost never break; auto-merge passing CI saves review cycles |
| Gradle wrapper | `matchManagers: ["gradle-wrapper"]` + `groupName: "gradle-wrapper"` | Solo group, separate from inline-deps for clearer blast radius |
| Mockito + ByteBuddy | `matchPackageNames: ["org.mockito:mockito-core", "org.mockito:mockito-junit-jupiter", "net.bytebuddy:byte-buddy*"]` + `dependencyDashboardApproval: true` | JDK 25 + inline mock maker fragility (see `build.gradle.kts:34-38`) — require explicit dashboard approval before PR opens |
| Java major | `matchManagers: ["sdkmanrc"]` + `matchUpdateTypes: ["major"]` + `dependencyDashboardApproval: true` | JDK majors are toolchain-wide events; never auto-PR |
| Everything else | default | Standard Renovate behaviour |

**Schedule: `["before 6am on monday"]` (server timezone UTC)**
Single weekly run. Burst on Monday morning, reviewed during the week. Alternative: `every weekday before 6am` — rejected, too noisy for a small repo. The dependency dashboard stays available for on-demand updates.

**`prConcurrentLimit: 5`, `prHourlyLimit: 2`**
Caps reviewer load and CI minutes. New PRs queue rather than flooding.

**Vulnerability alerts always on**
`vulnerabilityAlerts.enabled: true`, no schedule restriction — security fixes bypass weekly window.

## Risks / Trade-offs

- **Renovate app not installed** → All config is dormant until a repo admin installs the GitHub App at https://github.com/apps/renovate. The proposal flags this as out-of-band; merging this change without installing the app is benign (just no PRs).
- **Spring Boot grouping hides a transitive break** → Mitigation: CI runs full build+test on every Renovate PR; groupName doesn't change blast radius, just review surface. If a Boot upgrade breaks one module, the PR fails CI and we split it manually.
- **Auto-merge on GHA could merge during off-hours without human eyes** → Acceptable: CI must pass, and rollback = revert the PR. Limited to patch+minor (no major).
- **Mockito gated on dashboard approval may go stale** → Acceptable trade-off. The dashboard issue surfaces queued upgrades; once JDK 25 + Mockito stabilizes upstream, drop the `dependencyDashboardApproval` rule.
- **Java 25 in `.sdkmanrc` isn't a real Renovate-managed ecosystem unless `customManagers` is configured** → Use Renovate's built-in `sdkmanrc` manager (supported since 2023). If it does not detect updates, fall back to a `customManagers` regex — flagged as open question.

## Open Questions

- Should we add a `customManagers` block now in case the built-in `sdkmanrc` manager misses Java updates, or wait for first-run telemetry from the dependency dashboard? Defer until first run.
- Do we want to enable `lockFileMaintenance` for Gradle once a `libs.versions.toml` lands? Out of scope here, revisit when catalog migration happens.
