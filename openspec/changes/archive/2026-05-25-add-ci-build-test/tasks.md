## 1. Workflow file

- [x] 1.1 Create `.github/workflows/` directory
- [x] 1.2 Add `build.yml` with `name: build` and `on: [push, pull_request]` triggers (pull_request scoped to `branches: [main]`)
- [x] 1.3 Define single job `build` on `ubuntu-latest` with `timeout-minutes: 20`

## 2. Job steps

- [x] 2.1 Add `actions/checkout@v4` step
- [x] 2.2 Add `actions/setup-java@v4` step with `distribution: temurin`, `java-version: '25'`
- [x] 2.3 Add `gradle/actions/setup-gradle@v4` step (handles wrapper validation + dependency cache)
- [x] 2.4 Add build step: `./gradlew --no-daemon --stacktrace build`
- [x] 2.5 Add `actions/upload-artifact@v4` step with `if: failure()`, `name: test-reports`, paths `**/build/reports/tests/test/**` and `**/build/test-results/test/**`, `retention-days: 7`

## 3. Verify

- [x] 3.1 Lint workflow YAML locally (`yamllint` or paste into GitHub Actions editor)
- [ ] 3.2 Commit and push the workflow on this branch; observe a green run on Actions tab
- [ ] 3.3 Introduce a deliberately failing test in a throwaway commit, push, confirm workflow fails AND `test-reports` artifact is uploaded; then revert the failing commit
- [ ] 3.4 Open a draft PR against `main` to confirm the `pull_request` trigger fires
