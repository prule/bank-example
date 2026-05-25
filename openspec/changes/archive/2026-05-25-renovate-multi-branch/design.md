## Context

Existing Renovate config ([renovate.json](../../../renovate.json)) was authored in [add-renovate-config](../archive/2026-05-25-add-renovate-config/). Renovate defaults to the repository's `defaultBranch` (here, `main`). The study repo's actual work happens on long-lived branches `v1-basic` and `v2-openspec-claude`; `main` is largely a baseline. Without `baseBranches`, Renovate ignores those work branches entirely.

Renovate's `baseBranches` option accepts an array of branch names (or regex). When set, Renovate evaluates each branch independently and opens PRs targeting each.

## Goals / Non-Goals

**Goals:**
- Renovate runs against both `v1-basic` and `v2-openspec-claude`
- PRs from each base branch are distinguishable in titles and branch names
- Per-base-branch config overrides remain possible (future-proofing) without forking the file

**Non-Goals:**
- Including `main` in the scan list — it's a baseline branch and not where dependency upgrades land
- Adding more base branches now (e.g., release branches) — extend the list later if/when those exist
- Migrating either branch to a common base — branches stay parallel
- Changing grouping, scheduling, or approval rules

## Decisions

**Use literal branch names, not regex**
Only two branches are in scope and both are stable named branches. Regex (`/^v\\d+-/`) would also work but adds opacity. Alternative considered: regex with `additionalBranchPrefix` — rejected, two literals is clearer.

**`useBaseBranchConfig: "merge"`**
When Renovate reads config from a base branch, it merges with the default-branch config. Lets us, in future, ship branch-specific tweaks (e.g., pin Spring Boot on `v1-basic`) by committing a delta `renovate.json` to that branch without re-stating the whole config. Alternative: `"none"` (default) — keeps per-branch overrides impossible, rejected.

**`additionalBranchPrefix: "{{baseBranch}}-"`**
Renovate substitutes the base branch name into the generated branch prefix, e.g. `renovate/v1-basic-spring-boot` vs `renovate/v2-openspec-claude-spring-boot`. Without this, the two streams would collide and Renovate would skip the second. Standard Renovate idiom for multi-base setups.

**Leave `prConcurrentLimit: 5` unchanged**
The existing cap was chosen for one stream. Two streams could in theory queue twice as much — but practical experience shows Renovate doesn't open 5 PRs/week against this repo's small dependency surface. Revisit if the dashboard starts holding back significant work.

## Risks / Trade-offs

- **PR doubling on big release waves** → Mitigation: existing `prConcurrentLimit` + `prHourlyLimit` caps still apply per run; backlog goes to the dependency dashboard. Revisit limits if either branch develops sustained backlog.
- **Config drift between branches** → Once `useBaseBranchConfig: "merge"` is on, a future commit to `v1-basic`'s `renovate.json` could shadow shared rules unintentionally. Mitigation: keep `renovate.json` identical across branches unless intentionally overriding; the merge semantics make accidental overrides visible in `git diff renovate.json`.
- **Spec sync only happens on the branch where this change is implemented** → Since we apply on `v2-openspec-claude`, the `renovate.json` change must also be ported to `v1-basic` (cherry-pick or rebase). Out of scope to automate; called out in tasks.
- **Main branch left unscanned** → Intentional. If `main` ever becomes a real release branch, add it to `baseBranches`.

## Open Questions

- Do we want `main` included as a safety net? Current call: no. Re-evaluate if `main` becomes load-bearing.
