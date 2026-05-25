## Why

No automated build verification on branch pushes. Bugs and broken tests reach `main` without signal. Need GitHub Actions workflow to compile and run tests on every push and pull request so regressions are caught before review.

## What Changes

- Add `.github/workflows/build.yml` running `./gradlew build` on push and pull_request events
- Use Temurin JDK 25 (matches `.sdkmanrc`) via `actions/setup-java`
- Cache Gradle dependencies via `gradle/actions/setup-gradle` to speed reruns
- Upload JUnit test reports as build artifacts on failure for debugging
- Run on `ubuntu-latest`

## Capabilities

### New Capabilities
- `ci-build`: GitHub Actions workflow that builds the multi-module Gradle project and executes the test suite on push and pull request, publishing test reports on failure.

### Modified Capabilities
<!-- none -->

## Impact

- New file: `.github/workflows/build.yml`
- No code changes to application modules
- First push after merge will trigger workflow; subsequent runs benefit from Gradle dependency cache
- Adds GitHub Actions minutes consumption (free tier for public repos)
