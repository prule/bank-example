## ADDED Requirements

### Requirement: Build on push and pull request

The system SHALL run a GitHub Actions workflow that executes `./gradlew build` whenever code is pushed to any branch or a pull request is opened, synchronized, or reopened against `main`.

#### Scenario: Push to feature branch triggers build

- **WHEN** a developer pushes one or more commits to any branch in the repository
- **THEN** GitHub Actions SHALL start the `build` workflow on `ubuntu-latest`
- **AND** the workflow SHALL run `./gradlew --no-daemon build` and report success or failure on the commit status

#### Scenario: Pull request triggers build

- **WHEN** a pull request targeting `main` is opened, updated with new commits, or reopened
- **THEN** GitHub Actions SHALL start the `build` workflow
- **AND** the workflow result SHALL appear as a required-style check on the pull request

### Requirement: Use Java 25 Temurin toolchain

The workflow SHALL provision Java 25 from the Temurin distribution before invoking Gradle, matching the project's declared toolchain in `build.gradle.kts` and `.sdkmanrc`.

#### Scenario: Java setup uses Temurin 25

- **WHEN** the workflow runs
- **THEN** `actions/setup-java@v4` SHALL be invoked with `distribution: 'temurin'` and `java-version: '25'`
- **AND** `JAVA_HOME` SHALL point to that JDK for the Gradle step

### Requirement: Cache Gradle dependencies and validate wrapper

The workflow SHALL cache Gradle's dependency and build caches across runs to keep CI duration short, and SHALL validate the Gradle wrapper JAR checksum on every run.

#### Scenario: Cache hit on repeat run

- **WHEN** a workflow run starts after a previous successful run on the same OS with unchanged Gradle dependency declarations
- **THEN** Gradle dependencies SHALL be restored from cache rather than re-downloaded

#### Scenario: Wrapper validation runs

- **WHEN** the workflow checks out the repository
- **THEN** `gradle/actions/setup-gradle@v4` SHALL validate `gradle/wrapper/gradle-wrapper.jar` against the published Gradle checksum before any build step runs
- **AND** the workflow SHALL fail immediately if validation fails

### Requirement: Publish test reports on failure

When the build step fails, the workflow SHALL upload JUnit XML results and HTML test reports from every subproject as a build artifact so failures are debuggable from the GitHub Actions UI.

#### Scenario: Test failure publishes reports

- **WHEN** `./gradlew build` exits non-zero because one or more tests failed
- **THEN** the workflow SHALL upload `**/build/reports/tests/test/**` and `**/build/test-results/test/**` as an artifact named `test-reports`
- **AND** the artifact SHALL be retained for at least 7 days

#### Scenario: Green build skips upload

- **WHEN** `./gradlew build` succeeds
- **THEN** no test-report artifact SHALL be uploaded
