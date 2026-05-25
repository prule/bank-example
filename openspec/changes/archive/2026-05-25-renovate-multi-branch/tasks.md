## 1. Update renovate.json

- [x] 1.1 Add `"baseBranches": ["v1-basic", "v2-openspec-claude"]` to the top-level config
- [x] 1.2 Add `"useBaseBranchConfig": "merge"`
- [x] 1.3 Add `"additionalBranchPrefix": "{{baseBranch}}-"` so PR branch names are unique per base branch

## 2. Validate

- [x] 2.1 Run `npx --yes --package renovate -- renovate-config-validator renovate.json` — must pass with no errors
- [x] 2.2 Confirm JSON well-formed (`python3 -c "import json; json.load(open('renovate.json'))"`)

## 3. Port to `v1-basic`

- [ ] 3.1 After this change merges on `v2-openspec-claude`, cherry-pick (or replicate) the `renovate.json` edit onto `v1-basic` so both branches carry the same config
- [ ] 3.2 Push `v1-basic` so Renovate sees the new `baseBranches` on whichever branch it reads its config from

## 4. Post-merge verification

- [ ] 4.1 Wait for next Renovate run; confirm Dependency Dashboard issue lists updates grouped by base branch (sections per branch, or branch name in PR titles)
- [ ] 4.2 Spot-check that a Renovate PR opened against `v1-basic` AND one against `v2-openspec-claude` with non-colliding branch names
