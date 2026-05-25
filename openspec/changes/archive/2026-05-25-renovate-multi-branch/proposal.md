## Why

Renovate (per [dependency-updates](../../specs/dependency-updates/spec.md)) defaults to the repository's default branch only. This repo keeps two long-lived study branches in parallel — `v1-basic` and `v2-openspec-claude` — and dependency drift on either is a problem we want PRs against. Right now only `main` would receive Renovate PRs, so the work branches go stale and the whole point of automated updates is undermined.

## What Changes

- Add `baseBranches: ["v1-basic", "v2-openspec-claude"]` to `renovate.json`
- Enable `useBaseBranchConfig: "merge"` so per-branch config overrides are possible later without duplication
- Set PR titles / branch prefixes to include the base branch so `v1` and `v2` Renovate PRs don't collide in the UI
- **MODIFIED Capability** `dependency-updates`: scope of "what Renovate scans" expands from the default branch to an explicit list of base branches

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `dependency-updates`: change Renovate's scan target from default-branch-only to an explicit `baseBranches` list (`v1-basic`, `v2-openspec-claude`).

## Impact

- Modified file: `renovate.json` (add `baseBranches` + `useBaseBranchConfig`)
- Updated spec: `openspec/specs/dependency-updates/spec.md`
- Each Renovate cycle now opens up to N×2 PRs (one per base branch) where it previously opened N. With `prConcurrentLimit: 5` already in place this is bounded but expect more dashboard activity.
- PR branch names will be prefixed `renovate/v1-basic-...` and `renovate/v2-openspec-claude-...` so the two streams don't merge.
- `main` is NOT in the list — by design, since neither branch tracks it directly and main is currently a sample state.
