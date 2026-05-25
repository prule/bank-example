## Why

Dependencies in `build.gradle.kts` files, the Gradle wrapper, and GitHub Actions versions drift out of date silently. Spring Boot 3.4.5 already trails the latest patch line, and we have no signal when CVEs land in transitive deps. Manual upgrades are error-prone and skipped under deadline pressure. Renovate Bot watches manifests, opens PRs grouped by ecosystem, and lets the new [ci-build](../../specs/ci-build/spec.md) workflow gate each PR — turning dependency hygiene into reviewable diffs instead of a chore.

## What Changes

- Add `renovate.json` (or `.github/renovate.json5`) at the repo root with the project's Renovate configuration
- Extend Renovate's `config:recommended` preset plus `:dependencyDashboard` for visibility
- Enable the Gradle, Gradle wrapper, GitHub Actions, and `.sdkmanrc` managers (covers all current manifests)
- Group updates: one PR per Spring Boot release, one for GitHub Actions, one for non-major Gradle libs; major bumps stay solo for explicit review
- Schedule runs to weekday early mornings UTC + concurrent PR limit of 5 to avoid PR noise
- Pin Mockito and ByteBuddy to **manual review** (Java 25 + inline mock maker fragility per existing comment in `build.gradle.kts`)
- Auto-merge patch updates for GitHub Actions only, behind passing CI

## Capabilities

### New Capabilities
- `dependency-updates`: automated dependency update PRs raised by Renovate against this repo, grouped, scheduled, and gated by CI.

### Modified Capabilities
<!-- none -->

## Impact

- New file: `renovate.json` at repo root (or `.github/renovate.json5`)
- No code changes to application modules
- Requires Renovate GitHub App installed on the repository (one-time admin action, outside this change)
- First Renovate run will likely open an "onboarding" / "dependency dashboard" issue and a batch of upgrade PRs; expect 5–15 PRs in the first wave that will be processed using the new CI workflow
- Increases CI minutes consumption modestly (each PR triggers `build`)
