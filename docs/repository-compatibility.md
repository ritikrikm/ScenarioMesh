# Repository Compatibility and Integration Guide

This document describes what a target repository should look like before ScenarioMesh is introduced, what ScenarioMesh inspects, what is currently supported, what is deliberately not assumed, when ScenarioMesh passes through instead of taking over, and how the architecture can expand in the future.

It is intentionally repository-neutral. ScenarioMesh is a platform integration, not a configuration written for one test repository.

## 1. The central compatibility rule

ScenarioMesh does not ask:

> "Does this repository look exactly like one known sample?"

It asks:

> "What Maven test execution is actually going to happen, which framework owns those tests, and can ScenarioMesh reproduce that execution safely?"

A repository does not need a specific feature layout, runner naming convention, glue package, browser, environment, or Examples-table shape.

## 2. Typical repository before ScenarioMesh

A target Maven repository may be very simple:

```text
project/
├── pom.xml
├── src/
│   ├── main/
│   └── test/
│       ├── java/
│       └── resources/
└── ...
```

or considerably more complex:

```text
project/
├── pom.xml
├── config/
├── src/
│   ├── main/
│   └── test/
├── generated test sources/resources during build
├── custom Maven plugins
├── Surefire configuration
├── Failsafe configuration
└── reporting/framework configuration
```

Both are valid starting points. ScenarioMesh must inspect the effective build rather than require the repository to be redesigned around ScenarioMesh.

## 3. Minimal ScenarioMesh integration shape

A Maven repository using the Core Extension normally gains ScenarioMesh integration/configuration files such as:

```text
project/
├── pom.xml
├── scenariomesh.yml
├── .mvn/
│   └── extensions.xml
├── src/
└── ...
```

`scenariomesh.yml` contains repository-owned ScenarioMesh settings. `.mvn/extensions.xml` allows Maven to load the ScenarioMesh Core Extension early enough to observe the lifecycle.

The test source itself should not need conversion merely to use ScenarioMesh.

## 4. Example configuration

A representative configuration is:

```yaml
scenariomesh:
  configVersion: 1
  enabled: true

  execution:
    adapter: auto
    adapterMismatchPolicy: fail

  workers:
    count: 4
    startupTimeout: PT30S
    shutdownTimeout: PT15S
    jvmArgs: []

  discovery:
    timeout: PT2M

  reporting:
    directory: target/scenariomesh

  logging:
    liveConsole: true
    workerFiles: true
    showConfiguration: true
    showProgress: true
```

The exact operational values are configuration, not architectural assumptions. Teams can change them without changing scheduler/worker/adapter code.

## 5. Maven integration: Surefire and Failsafe

### Surefire

Surefire normally executes tests in the Maven `test` phase. It can be used with JUnit Platform/Jupiter, JUnit 4 providers, TestNG, Cucumber arrangements, and other provider configurations.

ScenarioMesh does not equate "Surefire" with "JUnit 5". The Maven executor and test framework are separate concerns.

### Failsafe

Failsafe normally executes integration tests in `integration-test` and verifies results in `verify`.

ScenarioMesh does not equate "Failsafe" with "Cucumber" or with a filename ending in `_IT`. The effective plugin configuration determines selection.

### A repository may use both

For example:

```text
mvn verify
 |
 +-- test
 |    +-- Surefire execution -> JUnit Platform unit tests
 |
 +-- integration-test
 |    +-- Failsafe execution -> Cucumber tests
 |
 +-- verify
      +-- Failsafe verification
```

A generic integration must keep those execution scopes distinct. ScenarioMesh must not suppress both simply because one is supported.

## 6. What "supported Surefire/Failsafe execution" means

ScenarioMesh only takes over an execution when it understands enough of that execution's effective semantics to preserve correctness.

Compatibility analysis can include:

- lifecycle phase/goals participating in the current Maven invocation;
- includes and excludes/test selection;
- relevant system properties;
- supported `argLine` JVM arguments;
- supported failure behavior such as `testFailureIgnore`;
- supported retry semantics;
- framework/provider evidence;
- active Maven properties/profiles that affect execution;
- prevention of duplicate execution after takeover.

A setting merely being present must not be ignored if it could change which tests run or how they behave.

If ScenarioMesh cannot reproduce the semantics safely, the correct behavior is pass-through or a clear compatibility failure according to the integration policy.

## 7. Important current Failsafe/Surefire safety behavior

The current product deliberately treats compatibility conservatively. Supported settings are translated into typed ScenarioMesh execution settings rather than copied as arbitrary maps.

For example:

```text
argLine
 -> supported arguments are forwarded to relevant JVM execution

systemPropertyVariables
 -> forwarded through the ScenarioMesh discovery/worker path as required

testFailureIgnore
 -> affects test-failure build semantics, not infrastructure failures

rerunFailingTestsCount
 -> modeled as logical Maven reruns, independently from infrastructure retries

failOnFlakeCount
 -> evaluated against the logical flaky-test count and reflected in Maven build/report semantics
```

This prevents ScenarioMesh from accidentally running scenarios twice or changing Maven behavior merely to increase compatibility coverage.

## 8. Test naming is not a compatibility requirement

ScenarioMesh must not require names such as:

```text
*Test
*IT
Runner_*
*_scenario001_run001_IT
```

Those may be conventions used by Maven defaults or repository generators, but Maven configuration can change them.

The correct generic model is:

```text
effective Maven includes/excludes/provider selection
        |
        v
candidate compiled test execution scope
        |
        v
framework-native adapter discovery
```

A custom runner name is acceptable when the effective Maven/framework configuration exposes it through a supported execution path.

## 9. Feature-file layout is not a compatibility requirement

ScenarioMesh does not require:

```text
src/test/resources/features
```

and does not require a particular Gherkin structure.

A repository may contain:

- ordinary `Scenario` definitions;
- `Scenario Outline` plus one or many `Examples` blocks;
- one or many Examples columns;
- Rules/Backgrounds/tags/DataTables;
- generated feature resources;
- feature resources in custom locations understood by the framework.

Because ScenarioMesh delegates discovery to the framework adapter, it should not need to understand the contents as a home-grown Gherkin parser.

## 10. Generated runners are optional, not required

Some repositories generate runners/resources before test execution:

```text
source feature
 -> build-time generator
 -> generated execution-specific feature/resource
 -> generated runner class
 -> Maven test executor
```

Other repositories use one static runner exposing many tests:

```text
one runner
 -> feature A / scenario 1
 -> feature A / scenario 2
 -> feature B / scenario 3
```

Others use JUnit Platform with no JUnit 4 runner class at all.

ScenarioMesh must not assume one runner equals one task or one feature equals one runner.

## 11. Supported adapter families

### 11.1 JUnit Platform adapter

Use when executable tests are exposed through JUnit Platform.

Typical supported worlds include:

```text
JUnit Jupiter/JUnit 5
 -> JUnit Platform

Cucumber Engine
 -> JUnit Platform
```

The adapter uses JUnit Platform Launcher discovery and the resulting TestPlan/UniqueIds. It does not parse Java source or Gherkin.

### 11.2 Cucumber JUnit 4 adapter

Use for legacy Cucumber exposed through JUnit 4 runners, including supported generated-runner structures.

The adapter uses JUnit 4/Cucumber runner/Description semantics. It must preserve distinct native executable leaves even when display names repeat.

### 11.3 TestNG adapter

Use for tests exposed through the supported TestNG runtime path.

The adapter translates TestNG's class/method/invocation model into ScenarioMesh tasks rather than assuming TestNG uses Cucumber/JUnit terminology.

## 12. Adapter auto-detection versus explicit configuration

`adapter: auto` asks ScenarioMesh to collect evidence and select a supported adapter when the result is safe and unambiguous.

An explicit adapter, for example:

```yaml
execution:
  adapter: cucumber-junit4
```

is useful when a repository owner knows the intended framework path. Explicit configuration is still validated. It is not permission to force a Cucumber JUnit 4 adapter over a repository that actually exposes only JUnit Platform tests.

The mismatch policy controls how disagreement is handled.

## 13. Why dependency presence alone is not enough

A repository can contain all of these jars simultaneously:

```text
JUnit 4
JUnit Platform/Jupiter
TestNG
Cucumber
Selenium
```

That does not mean every framework participates in the requested Maven execution. Libraries may be transitive, retained for compatibility, used only by tooling, or used by different Maven scopes.

Adapter selection therefore uses execution/discovery evidence rather than `jar exists -> choose adapter`.

## 14. Execution identity requirements

ScenarioMesh requires a stable-enough native execution identity for every task.

Display names are insufficient because these can legitimately repeat:

- Scenario Outline rows;
- parameterized JUnit tests;
- dynamic tests;
- TestNG DataProvider invocations;
- generated feature/runner copies;
- tests with identical method/scenario names in different containers.

Adapter-specific identity examples:

```text
JUnit Platform
 -> framework UniqueId

Cucumber JUnit 4
 -> runner/native leaf selector plus source evidence when available

TestNG
 -> class + method + invocation/data identity when available
```

There is intentionally no universal core formula such as `runner + scenario name`.

If a supported adapter cannot uniquely distinguish executable units, discovery must fail clearly instead of silently dropping or duplicating them.

## 15. Worker compatibility assumptions

The current worker implementation uses isolated local JVM processes.

This supports repositories that may have in-process mutable state such as:

```text
static WebDriver
static test DTOs
singletons
in-memory caches
global framework state
```

because each worker process has a separate heap.

It does not automatically solve conflicts in external shared resources such as:

```text
same login/test user
same database row
same account
same environment capacity
same limited external service/license
```

Resource-aware scheduling is the architectural solution for those constraints and is a future capability beyond simple FIFO.

## 16. Current scheduler compatibility

The current scheduler is dynamic FIFO.

This requires no historical timing data and is deterministic/simple:

```text
next READY worker
 -> next queued eligible task
```

The scheduler does not contain Cucumber, Maven, ProcessBuilder, or TestNG logic. This allows future strategies such as:

- duration-aware/LPT;
- resource-aware scheduling;
- worker affinity;
- browser/capability-aware assignment;
- historical failure-aware ordering.

Those are future strategies, not claims that the current product already implements them.

## 17. Logging/reporting compatibility

Worker stdout/stderr is separate from ScenarioMesh protocol traffic.

Configuration can independently enable:

```yaml
logging:
  liveConsole: true
  workerFiles: true
```

This allows local Maven and Jenkins-style consoles to receive live target-framework output while preserving per-worker diagnostic files.

Reports distinguish test failures from infrastructure errors. Infrastructure errors represent ScenarioMesh/worker/execution failures, not failed assertions.

## 18. CI/Jenkins compatibility model

The local worker-process architecture is suitable for noninteractive CI execution when ScenarioMesh artifacts/configuration are available to Maven and the agent can start child Java processes.

A CI-safe run should conceptually be:

```text
fresh/ephemeral agent
 -> checkout
 -> Maven starts
 -> ScenarioMesh extension loads
 -> build/generation/compilation
 -> supported execution takeover
 -> ephemeral local worker JVMs
 -> reports inside workspace
 -> bounded shutdown
 -> Maven exit status
```

ScenarioMesh should not require a developer to manually start workers before a CI build.

CI provider-specific behavior should not be hard-coded into scheduler/core modules.

## 19. Current supported path summary

The current product is intended to support, within the implemented compatibility checks:

- Maven lifecycle integration through the ScenarioMesh extension/plugin architecture;
- standard Maven commands rather than a mandatory wrapper command;
- supported Surefire execution scopes;
- supported Failsafe integration-test/verify execution scopes;
- JUnit Platform/Jupiter discovery/execution path;
- Cucumber through JUnit Platform when exposed as an engine through the supported path;
- legacy Cucumber JUnit 4 through its dedicated adapter;
- supported TestNG path;
- generated Cucumber/JUnit 4 runner structures where native executable identity is distinguishable;
- repositories where one runner exposes multiple tests;
- configurable local isolated JVM workers;
- dynamic FIFO scheduling;
- live and per-worker logging;
- HTML/machine-readable result reporting;
- deterministic failure propagation to Maven.

"Supported" is always subject to the actual effective Maven/framework semantics being within the implementation's compatibility envelope. ScenarioMesh should not claim support merely because the repository contains a named dependency.

## 20. Not currently guaranteed / deliberate product boundaries

The product does not claim universal support for:

- Gradle lifecycle integration;
- arbitrary proprietary test frameworks without an adapter;
- arbitrary custom JUnit 4 runners whose semantics the Cucumber/JUnit 4 adapter does not understand;
- unknown/custom Surefire or Failsafe provider behavior that ScenarioMesh cannot reproduce;
- every possible Surefire/Failsafe configuration element;
- arbitrary retry providers or retry configurations outside the proven Surefire/Failsafe logical-rerun contract;
- ambiguous multiple Maven test executions where safe ownership cannot be determined;
- remote worker hosts;
- Docker/Kubernetes worker launchers;
- persistent cross-run worker daemon/reuse as a completed production capability unless explicitly implemented and validated;
- resource-aware scheduling as a completed current strategy;
- automatic isolation of external test accounts/data/environments;
- a Gherkin parser or Java source parser;
- Selenium/WebDriver replacement;
- fixing broken teardown inside a target test framework.

These boundaries should result in safe pass-through or actionable failure, not silent behavior changes.

## 21. Future compatibility directions

The architecture is intentionally arranged so future capabilities can be added behind focused interfaces rather than rewriting core execution.

### Build integrations

```text
Maven integration (current)
Gradle integration (future)
```

### Worker launchers

```text
LocalJvmWorkerLauncher (current)
DockerWorkerLauncher (future)
RemoteWorkerLauncher (future)
KubernetesWorkerLauncher (future)
```

### Scheduling

```text
FIFO (current)
DurationAware/LPT (future)
ResourceAware (future)
AffinityAware (future)
CapabilityAware (future)
```

### Framework adapters

New frameworks/engines can implement the discovery/execution contracts without adding framework-specific conditionals to core scheduling.

### Observability

Current logs/reports can evolve toward metrics, OpenTelemetry/Prometheus exporters, historical dashboards, and CI integrations through observability/reporting abstractions.

## 22. Repository onboarding checklist

Before enabling ScenarioMesh in a new repository, determine:

1. Which Maven goals are normally run locally and in CI?
2. Does Surefire participate, Failsafe participate, or both?
3. Are there multiple executions of either plugin?
4. What effective includes/excludes select the tests?
5. Which framework actually owns those selected tests?
6. Is it JUnit Platform, legacy Cucumber/JUnit 4, TestNG, or something unsupported?
7. Are test sources/runners/features generated before execution?
8. Does one runner expose multiple executable leaves?
9. Are Scenario Outlines/parameterized/DataProvider invocations present?
10. Can the adapter obtain a unique native execution identity?
11. Which system properties/JVM arguments are required?
12. Are retry/failure-ignore semantics configured?
13. Are there shared external resources that make unrestricted parallelism unsafe?
14. Can CI agents launch the configured number of Java child processes?
15. Are reports/log paths writable and archived as required?

ScenarioMesh should automate as much of this evidence gathering as possible. Explicit configuration exists for cases where automation cannot safely decide.

## 23. Example compatibility decisions

### Case A - JUnit Jupiter + Surefire

```text
Surefire selected by Maven
 -> JUnit Platform available
 -> Launcher discovers selected Jupiter tests
 -> required Surefire semantics supported
 -> TAKE OVER
```

### Case B - Cucumber Engine + JUnit Platform

```text
Maven execution selected
 -> JUnit Platform available
 -> Cucumber Engine appears in TestPlan
 -> native UniqueIds available
 -> TAKE OVER when Maven semantics are supported
```

### Case C - generated Cucumber JUnit 4 runners + Failsafe

```text
generation/compilation happens normally
 -> Failsafe selects generated runners
 -> Cucumber JUnit 4 adapter discovers native leaves
 -> repeated display names remain distinct via native execution identity
 -> TAKE OVER when Failsafe semantics are supported
```

### Case D - Surefire and Failsafe both participate

```text
Surefire scope -> independently analyze
Failsafe scope -> independently analyze

one supported does not automatically imply the other is suppressed
```

### Case E - unknown custom provider configuration

```text
ScenarioMesh cannot determine equivalent behavior
 -> PASS THROUGH / actionable compatibility failure
 -> never guess
```

## 24. What ScenarioMesh deliberately does not require from a repository

A repository does not need to:

- rename every test to `*IT`;
- move features to one fixed directory;
- use one Examples column;
- generate one runner per scenario;
- migrate JUnit 4 to JUnit 5 solely for ScenarioMesh;
- replace Selenium/WebDriver;
- remove static fields before obtaining process isolation;
- manually construct worker classpaths;
- manually start workers for every Maven run;
- rewrite tests into ScenarioMesh APIs.

The product principle is that ScenarioMesh should behave like infrastructure underneath a supported test suite.

## 25. Short compatibility explanation

> ScenarioMesh does not decide compatibility from filenames or one repository layout. It observes the effective Maven execution, checks whether it can preserve the participating Surefire/Failsafe semantics, then delegates test discovery and execution to a framework-specific adapter using native APIs. If the execution can be represented safely as uniquely identified ScenarioTasks, ScenarioMesh can schedule those tasks across isolated JVM workers. If it cannot prove enough equivalence, it passes through or fails clearly rather than changing test behavior.
