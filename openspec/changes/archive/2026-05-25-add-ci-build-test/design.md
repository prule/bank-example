## Context

Multi-module Gradle project (Spring Boot 3.4.5, Java 25 Temurin per `.sdkmanrc`). Subprojects: `application`, `bootstrap`, `domain`, `infrastructure`. Tests use JUnit Platform with Mockito inline mock maker (requires `net.bytebuddy.experimental=true` already set in root `build.gradle.kts`). Currently no CI — builds happen only on developer machines. Branch under review (`v2-openspec-claude`) needs automated verification before merge to `main`.

## Goals / Non-Goals

**Goals:**
- Run `./gradlew build` (compile + test) on every push to any branch and every pull request targeting `main`
- Cache Gradle dependencies and wrapper to keep CI cycles short
- Surface JUnit test reports when a build fails so failures are debuggable from the Actions UI
- Use Java 25 Temurin to match local dev toolchain

**Non-Goals:**
- Deploy / release automation
- Code coverage reporting, static analysis, dependency scanning
- Matrix builds across JDK versions or OSes
- Branch protection rule changes (left to repo admin)

## Decisions

**JDK 25 Temurin via `actions/setup-java@v4`**
Matches `.sdkmanrc` (`java=25.0.3-tem`). Gradle toolchain in `build.gradle.kts` pins Java 25 so the runner must expose a matching JDK. Alternative: rely solely on Gradle toolchain auto-provisioning — rejected because download adds ~30s per run and is less deterministic.

**`gradle/actions/setup-gradle@v4` for cache + wrapper validation**
Official Gradle action handles dependency cache, build cache, and validates the wrapper JAR checksum (supply-chain protection). Alternative: manual `actions/cache` keyed on `*.gradle.kts` + `gradle-wrapper.properties` — rejected, more code for less protection.

**Trigger on `push` and `pull_request`**
`push` catches direct commits to any branch (including this `v2-openspec-claude` branch). `pull_request` ensures fork PRs also run. Alternative: only `pull_request` — rejected, would skip pushes to non-PR branches.

**Single `ubuntu-latest` runner**
Project has no OS-specific code. Adding macOS/Windows triples cost with no signal.

**Upload test reports on failure only**
Use `if: failure()` with `actions/upload-artifact@v4` pointing at `**/build/reports/tests/test` and `**/build/test-results/test`. Avoids artifact storage cost on green builds.

## Risks / Trade-offs

- **Java 25 availability on `ubuntu-latest`** → Temurin 25 GA shipped in 2025; `actions/setup-java@v4` supports it. Mitigation: pin `java-version: '25'` and `distribution: 'temurin'`; if missing, fall back to `'25-ea'`.
- **Gradle wrapper validation failure** → If `gradle-wrapper.jar` checksum drifts, the workflow blocks. Mitigation: this is the desired behavior; treat any validation failure as security signal, not noise.
- **First-run cache miss is slow (~3-5 min for deps)** → Acceptable one-time cost; subsequent runs hit cache and finish in ~1-2 min.
- **Mockito inline + JDK 25 instability** → Already mitigated in `build.gradle.kts` with `net.bytebuddy.experimental=true`. If tests flake in CI but pass locally, the root cause is likely upstream Mockito/ByteBuddy and not the workflow.
