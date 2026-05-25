## 1. Renovate config file

- [x] 1.1 Create `renovate.json` at repo root with `$schema: "https://docs.renovatebot.com/renovate-schema.json"`
- [x] 1.2 Extend `["config:recommended", ":dependencyDashboard", ":semanticCommits"]`
- [x] 1.3 Set `timezone: "Etc/UTC"`, `schedule: ["before 6am on monday"]`, `prConcurrentLimit: 5`, `prHourlyLimit: 2`
- [x] 1.4 Enable `vulnerabilityAlerts: { enabled: true }` with no schedule restriction

## 2. Manager coverage

- [x] 2.1 Confirm default managers include `gradle`, `gradle-wrapper`, `github-actions`, `sdkmanrc` (all enabled by default in `config:recommended`; no extra config needed unless validator complains)

## 3. packageRules

- [x] 3.1 Add rule: group Spring Boot + Spring Cloud (`matchPackagePrefixes` → `groupName: "spring-boot"`)
- [x] 3.2 Add rule: group GitHub Actions (`matchManagers: ["github-actions"]` → `groupName: "github-actions"`, `automerge: true`, `matchUpdateTypes: ["patch", "minor"]`)
- [x] 3.3 Add rule: Gradle wrapper solo group (`matchManagers: ["gradle-wrapper"]` → `groupName: "gradle-wrapper"`)
- [x] 3.4 Add rule: Mockito + ByteBuddy gated (`matchPackageNames` including `org.mockito:mockito-core`, `org.mockito:mockito-junit-jupiter`, and `matchPackagePrefixes: ["net.bytebuddy:"]` → `dependencyDashboardApproval: true`)
- [x] 3.5 Add rule: JDK major bumps gated (`matchManagers: ["sdkmanrc"]` + `matchUpdateTypes: ["major"]` → `dependencyDashboardApproval: true`)

## 4. Validate

- [x] 4.1 Run `npx --yes --package renovate -- renovate-config-validator renovate.json` and confirm no errors
- [x] 4.2 Spot-check the rendered JSON is well-formed (`python3 -c "import json; json.load(open('renovate.json'))"`)

## 5. Enablement (manual, outside this change)

- [ ] 5.1 Confirm Renovate GitHub App is installed on the repo (admin task; flag in PR description if not yet done)
- [ ] 5.2 After merge, check the "Dependency Dashboard" issue is opened by Renovate within ~1 day
- [ ] 5.3 Verify the first wave of Renovate PRs trigger the `build` workflow from [ci-build](../../specs/ci-build/spec.md)
