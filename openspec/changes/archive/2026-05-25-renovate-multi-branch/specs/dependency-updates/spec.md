## ADDED Requirements

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
