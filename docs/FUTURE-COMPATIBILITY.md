# ScenarioMesh Future Compatibility Roadmap

This document tracks compatibility areas discovered while validating ScenarioMesh against real Maven repositories.

The core rule remains:

> Prove compatibility, then take ownership. If compatibility cannot be proven, leave native Maven execution alone.

## 1. Custom Maven Surefire executions

### Motivation

Apache Commons Lang currently uses a named Surefire execution:

```xml
<execution>
  <id>plain</id>
  ...
</execution>
```

ScenarioMesh correctly selects `PASS_THROUGH` today because ownership of non-standard Surefire executions is not yet proven.

The future goal is to safely move compatible custom executions from:

`PASS_THROUGH -> SCENARIOMESH TAKEOVER`

without changing Maven test semantics.

### Required work

- Discover every effective `maven-surefire-plugin` execution independently.
- Preserve the execution id, lifecycle phase, goals, and effective configuration.
- Build a stable execution identity such as `surefire:plain`.
- Resolve execution-specific test selection:
  - `includes`
  - `excludes`
  - `includesFile`
  - `excludesFile`
  - `-Dtest`
  - nested-class scanning semantics
  - method-level selections
- Preserve JVM/runtime settings:
  - `argLine`
  - `systemPropertyVariables`
  - system properties files
  - environment variables where relevant
  - fork count
  - reuse-forks behavior
  - JVM selection
- Preserve Surefire provider/framework behavior.
- Detect multiple Surefire executions without merging their semantics accidentally.
- Prevent duplicate discovery/execution when executions overlap.
- Preserve skipped, failed, flaky, and rerun semantics.
- Preserve native Maven behavior when ownership cannot be proven.

### Ownership gate

ScenarioMesh should take ownership of a custom execution only when it can prove all required execution semantics are representable.

Otherwise it must emit a clear reason and use native Maven pass-through.

## 2. Apache Commons Lang takeover target

Pinned validation target discovered by the real-repository lab:

- Repository: `apache/commons-lang`
- Pinned commit used by the lab: `29624cdb50ecd794207d561345b2fc9ca3a9d326`
- Current native baseline observed by the lab:
  - 89,363 testcases
  - 89,346 passed
  - 17 skipped
  - 0 failed
- Current ScenarioMesh decision: `PASS_THROUGH`
- Reason: non-standard Surefire execution `plain`

### Acceptance criteria for future takeover

Before changing Commons Lang from pass-through to takeover:

1. Native Maven must pass from a clean pinned clone.
2. ScenarioMesh must identify the exact `plain` execution configuration.
3. ScenarioMesh must take ownership without modifying the upstream project.
4. Logical testcase count must equal the native Maven count.
5. Passed, skipped, and failed counts must match native Maven.
6. No test may run twice.
7. No native test may disappear.
8. Maven lifecycle behavior must remain equivalent.
9. Regression fixtures must cover the custom-execution behavior independently of Commons Lang.
10. The complete ScenarioMesh CI gate and post-merge `main` validation must remain green.

## 3. Multiple Surefire executions

Add fixtures and real-repository coverage for projects with multiple Surefire executions, including:

- separate executions with different include patterns
- executions bound to different lifecycle phases
- overlapping test classes
- different JVM arguments
- different system properties
- executions intentionally skipped by profile/property
- execution-specific provider configuration

ScenarioMesh must model each execution separately rather than flattening all Maven configuration into one test run.

## 4. Failsafe and `mvn verify`

Expand the real-repository lab beyond `mvn test`.

Future lab command modes:

- `test` for Surefire
- `verify` for Surefire + Failsafe/integration tests

Required support includes:

- `maven-failsafe-plugin`
- `integration-test` and `verify` lifecycle phases
- `target/failsafe-reports/TEST-*.xml`
- execution-specific includes/excludes
- pre/post-integration-test lifecycle behavior
- correct aggregation of Surefire and Failsafe results without double counting

## 5. Multi-module Maven repositories

Add mature multi-module repositories after command-mode/report aggregation support is ready.

Requirements:

- reactor-aware module identity
- per-module ownership decisions
- mixed takeover/pass-through in the same reactor
- recursive Surefire/Failsafe report aggregation
- no duplicate counting across modules
- correct handling of skipped modules and `-pl` / `-am`
- module-specific plugin configuration inheritance

## 6. Additional framework and Maven semantics

Continue expanding real-repository coverage for:

- larger JUnit Jupiter repositories
- JUnit Vintage / JUnit 4
- TestNG
- Cucumber JUnit Platform
- Cucumber JUnit 4
- Spring Boot projects
- projects using Maven profiles
- projects using inherited pluginManagement
- projects with non-default forks
- projects with parallel Surefire settings
- projects using custom providers
- projects with rerun/failure retry configuration

## 7. Real-repository validation policy

Every new real target should:

1. Use a public upstream repository.
2. Pin an exact commit.
3. Run a clean native Maven baseline.
4. Run a separate clean clone with ScenarioMesh enabled.
5. Never modify upstream test source or POM merely to force a green result.
6. Classify the result as:
   - proven takeover
   - proven safe pass-through
   - genuine compatibility bug
   - invalid upstream/native baseline
7. Fix genuine ScenarioMesh compatibility bugs with focused regression coverage before moving on.
8. Keep security-sensitive target URLs allowlisted/pinned rather than accepting arbitrary PR-provided repositories.

## 8. Long-term target

ScenarioMesh should gradually reduce safe pass-through cases as its Maven semantic model becomes stronger.

The desired end state is not unconditional takeover. The desired end state is:

**maximum proven ownership with zero silent semantic drift.**
