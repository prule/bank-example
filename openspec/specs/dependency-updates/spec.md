# Dependency Updates

## Purpose

Keep this repository's dependencies — Gradle libraries, the Gradle wrapper, GitHub Actions, and the JDK declared in `.sdkmanrc` — current and patched, by delegating discovery and PR creation to Renovate Bot under a configuration committed to the repo. Owns the contract for which managers are enabled, how updates are grouped, when PRs may open, which upgrades require human approval, and how security alerts bypass scheduling. Does not own CI gating itself — that is the [ci-build](../ci-build/spec.md) capability — but assumes every Renovate PR runs through it.

## Requirements

### Requirement: Renovate configuration committed to repo

The repository SHALL contain a Renovate configuration file at the repo root (`renovate.json`) that Renovate Bot picks up without additional path overrides.

#### Scenario: Renovate discovers config

- **WHEN** the Renovate GitHub App scans the repository
- **THEN** it SHALL load `renovate.json` from the repo root
- **AND** the config SHALL parse without validation errors (validated via `npx --yes --package renovate -- renovate-config-validator` or equivalent)

### Requirement: Managers cover every dependency manifest

The configuration SHALL enable Renovate managers that watch every dependency-bearing manifest in this repo: Gradle build scripts, the Gradle wrapper, GitHub Actions workflows, and the `.sdkmanrc` Java declaration.

#### Scenario: All current manifests are tracked

- **WHEN** Renovate runs against the repository
- **THEN** the `gradle`, `gradle-wrapper`, `github-actions`, and `sdkmanrc` managers SHALL each be enabled
- **AND** Renovate SHALL discover at minimum: the 5 `build.gradle.kts` files, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/*.yml`, and `.sdkmanrc`

### Requirement: Group updates by ecosystem

The configuration SHALL group related package updates into single pull requests to minimize review overhead while keeping unrelated ecosystems separate.

#### Scenario: Spring Boot upgrades arrive as one PR

- **WHEN** a new Spring Boot release introduces version bumps to `org.springframework.boot:*` and `org.springframework.cloud:*` packages
- **THEN** Renovate SHALL open exactly one pull request titled with the `spring-boot` group name covering all affected packages

#### Scenario: GitHub Actions updates arrive as one PR

- **WHEN** one or more `actions/*` or `gradle/actions/*` reference a newer version tag
- **THEN** Renovate SHALL open a single pull request grouped under `github-actions`

#### Scenario: Gradle wrapper is its own PR

- **WHEN** a new Gradle wrapper version is released
- **THEN** Renovate SHALL open a separate pull request for the wrapper, not bundled with library updates

### Requirement: Risky upgrades require explicit approval

The configuration SHALL gate Mockito, ByteBuddy, and JDK major updates behind the Renovate dependency dashboard so that no PR opens until a maintainer ticks the checkbox.

#### Scenario: Mockito update waits for approval

- **WHEN** a new Mockito or ByteBuddy version is released
- **THEN** Renovate SHALL list the update on the dependency dashboard issue with an approval checkbox
- **AND** no pull request SHALL open until the checkbox is ticked

#### Scenario: JDK major bump waits for approval

- **WHEN** the Temurin distribution publishes a new JDK major version (e.g., 26)
- **THEN** the update SHALL appear on the dependency dashboard with `dependencyDashboardApproval: true` and SHALL NOT open a PR automatically

### Requirement: Schedule and rate-limit PR creation

The configuration SHALL restrict Renovate to weekly off-hours runs and cap concurrent open PRs, so that dependency PRs do not crowd out feature work or saturate CI.

#### Scenario: PRs open in the scheduled window

- **WHEN** Renovate runs outside the scheduled window
- **THEN** it SHALL not open any new pull request

#### Scenario: Concurrent PR cap respected

- **WHEN** Renovate would open an 11th non-security pull request while 5 are already open
- **THEN** it SHALL hold the additional updates back, listing them on the dependency dashboard until a slot frees up
- **AND** `prConcurrentLimit` SHALL be set to 5 and `prHourlyLimit` SHALL be set to 2 or lower

### Requirement: Auto-merge GitHub Actions patch and minor updates

The configuration SHALL auto-merge GitHub Actions patch and minor updates after the `build` workflow passes, since action references rarely break.

#### Scenario: GHA patch update auto-merges

- **WHEN** Renovate opens a `github-actions` PR that contains only patch- or minor-level updates
- **AND** the `build` CI workflow on that PR succeeds
- **THEN** Renovate SHALL automatically merge the PR

#### Scenario: GHA major update does not auto-merge

- **WHEN** the same group contains a major-version bump
- **THEN** auto-merge SHALL NOT apply and the PR SHALL await human review

### Requirement: Security alerts bypass schedule

Vulnerability-driven updates SHALL open pull requests immediately, regardless of the weekly schedule or concurrent-PR cap.

#### Scenario: Security PR opens off-schedule

- **WHEN** GitHub publishes a security advisory affecting a dependency
- **AND** the current time is outside the configured weekly schedule
- **THEN** Renovate SHALL still open the security PR within its normal latency
- **AND** the PR SHALL be labelled or titled so reviewers can identify it as a security update

### Requirement: Dependency dashboard issue exists

Renovate SHALL maintain a single open "Dependency Dashboard" issue in the repository listing all queued updates, in-flight PRs, and approval-gated upgrades.

#### Scenario: Dashboard issue is created on first run

- **WHEN** Renovate runs for the first time against this repository
- **THEN** it SHALL open an issue titled "Dependency Dashboard"
- **AND** the issue SHALL remain open and be updated by Renovate on each subsequent run

### Requirement: Scan configured base branches

Renovate SHALL evaluate dependency updates against each branch listed in `baseBranches`, not only the repository's default branch, and SHALL open pull requests targeting the originating base branch.

#### Scenario: Both work branches are scanned

- **WHEN** Renovate runs against the repository
- **THEN** it SHALL evaluate updates separately for both `v1-basic` and `v2-openspec-claude`
- **AND** any PR it opens SHALL target the same base branch the update was discovered on

#### Scenario: Default branch alone is ignored when baseBranches is set

- **WHEN** `baseBranches` is configured
- **THEN** Renovate SHALL NOT open PRs targeting the repository default branch unless that branch is listed in `baseBranches`

### Requirement: PRs from different base branches do not collide

The configuration SHALL ensure Renovate-generated branch names are unique per base branch, so that an update to the same package on two different base branches produces two distinct PRs.

#### Scenario: Same upgrade on both branches opens two PRs

- **WHEN** the same dependency upgrade applies to both `v1-basic` and `v2-openspec-claude`
- **THEN** Renovate SHALL open two pull requests with distinct branch names (e.g., `renovate/v1-basic-<group>` and `renovate/v2-openspec-claude-<group>`)
- **AND** each PR SHALL target its respective base branch

### Requirement: Per-branch config overrides supported

The configuration SHALL allow future branch-specific Renovate overrides without duplicating the full configuration on each branch, by enabling base-branch config merging.

#### Scenario: Branch-local config layers on top of shared config

- **WHEN** a base branch contains its own `renovate.json` with a subset of fields different from the default branch
- **THEN** Renovate SHALL merge the branch-local config on top of the default-branch config rather than replacing it wholesale
- **AND** unchanged fields SHALL retain their default-branch values
