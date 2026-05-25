```text
commit and archive
```

OpenAPI fix committed. Now syncing the delta spec into the main spec, then archiving.

Now validate the spec, archive the change, and commit.

Synced and archived. Now commit.

Now the doc work the user has been curating.

## Archive Complete

**Change:** dev-data-seeding
**Schema:** spec-driven
**Archived to:** [openspec/changes/archive/2026-05-25-dev-data-seeding/](openspec/changes/archive/2026-05-25-dev-data-seeding/)
**Specs:** ✓ Synced into [openspec/specs/dev-data-seeding/spec.md](openspec/specs/dev-data-seeding/spec.md) — the four high-level requirements are now the refined implementation-precise versions, with cross-spec mentions converted to `[[capability-name]]` links per the openspec convention. `openspec validate --specs` passes for all 12 capabilities.

**Three commits:**
- `7af6a3b` docs(openapi): align example values with dev-data-seeding fixture
- `850b4a4` opsx:archive dev-data-seeding (spec sync + mv)
- `1ec2f1a` docs: add apply-modification sample output for dev-data-seeding

**Warnings:** Archived with 3 incomplete task lines (13.2/13.3/13.4 — interactive `bootRun` smoke tests). All automated tests (61 across the suite, 15 newly added) and `openspec validate` are green.