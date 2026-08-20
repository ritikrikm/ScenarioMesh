# ScenarioMesh Execution Flow

This document explains ScenarioMesh from the moment a developer clicks **Run** or invokes Maven until Maven receives the final build result. It deliberately separates Maven responsibilities, framework responsibilities, adapter responsibilities, ScenarioMesh responsibilities, and current capability boundaries.

## 1. One-sentence mental model

ScenarioMesh sits underneath an existing Maven test suite at the **test execution boundary**. Maven still performs its normal lifecycle work. Existing generators and compilers still run. ScenarioMesh takes ownership only when it can safely reproduce the participating Surefire/Failsafe execution, uses a framework adapter to discover native executable tests, converts them to framework-neutral tasks, distributes those tasks to isolated worker JVMs, collects results, writes reports, and returns the correct Maven outcome.

ScenarioMesh is not a Gherkin parser, Java source parser, Selenium replacement, or new test framework.

## 2. End-to-end flow

```text
Developer clicks Run / CI invokes Maven
        |
        v
Maven starts with the requested goals and -D properties
        |
        v
ScenarioMesh Maven Core Extension loads
        |
        v
ScenarioMesh observes the effective Maven execution plan
        |
        +--> ScenarioMesh disabled? -------------------- YES --> normal Maven execution
        |
        NO
        v
Maven continues normal pre-test lifecycle work
(clean, resources, compilation, generated sources, generated runners, etc.)
        |
        v
ScenarioMesh reaches a candidate test execution boundary
        |
        +--> Surefire execution(s)
        +--> Failsafe execution(s)
        |
        v
Compatibility / takeover analysis
        |
        +--> cannot safely reproduce semantics --------> PASS THROUGH to Maven plugin
        |
        v
Resolve ScenarioMesh configuration once
        |
        v
Determine candidate framework adapter(s)
        |
        +--> JUnit Platform
        +--> Cucumber JUnit 4
        +--> TestNG
        |
        v
Framework-native discovery
        |
        v
Native executable units -> ScenarioTask objects
        |
        v
Create isolated worker JVMs
        |
        v
Workers connect to coordinator and become READY
        |
        v
FIFO dynamic scheduler fills task queue
        |
        v
Coordinator assigns next task to each READY worker
        |
        v
Worker executes task through the matching framework adapter
        |
        +--> framework hooks/listeners/lifecycle
        +--> application/test framework
        +--> Selenium/browser code where applicable
        |
        v
Worker returns structured ExecutionResult
        |
        v
Worker becomes READY and immediately receives next task
        |
        v
All tasks terminal?
        |
        NO ----> continue dynamic assignment
        |
        YES
        v
Bounded worker shutdown
        |
        v
Aggregate results + generate HTML/JSON/JUnit reports
        |
        v
Map test/infrastructure outcome back to Maven
        |
        v
BUILD SUCCESS / BUILD FAILURE
```

## 3. Step 1 - Maven starts

A user continues to run the repository's normal command, for example:

```bash
mvn test
```

or:

```bash
mvn clean compile verify -Dsome.project.property=value
```

ScenarioMesh does not require a shell wrapper around `mvn`. The Maven Core Extension is loaded through Maven's extension mechanism, so ScenarioMesh can observe the Maven session and execution plan.

At this stage Maven owns the build. ScenarioMesh must not assume that every repository uses `test`, Surefire, Failsafe, JUnit 4, JUnit 5, Cucumber, a particular runner suffix, or a particular feature directory.

## 4. Step 2 - Maven performs normal lifecycle work

ScenarioMesh does not replace normal compilation or repository-specific generation.

Before test execution a repository may perform any supported Maven lifecycle work such as:

```text
clean
 -> generate-resources
 -> compile
 -> generate-test-sources
 -> process-test-resources
 -> test-compile
 -> test
 -> integration-test
 -> verify
```

A repository may generate no test artifacts at all, or it may generate feature copies, Java runners, configuration, test data, or other test sources. Those are repository/plugin responsibilities.

For example, one Cucumber repository may have one static runner that exposes many scenarios. Another may expand Scenario Outline example rows into generated feature files and generated JUnit 4 runners. ScenarioMesh must support framework semantics rather than depend on one generation convention.

## 5. Step 3 - Identify the Maven test execution boundary

ScenarioMesh examines the effective Maven plan, not merely the command text.

### Surefire

Surefire normally participates in Maven's `test` phase. A repository may use it for JUnit, TestNG, Cucumber, or another supported provider arrangement.

### Failsafe

Failsafe normally participates around `integration-test` and `verify`. A repository may use it for generated integration-test runners or ordinary integration tests.

### Both can exist

A repository can legitimately contain both:

```text
Surefire/test
 -> JUnit Platform unit tests

Failsafe/integration-test + verify
 -> Cucumber integration tests
```

ScenarioMesh therefore treats Maven plugin executions as execution scopes. It must not infer that finding Failsafe means Surefire is irrelevant, or vice versa.

## 6. Step 4 - Safe takeover analysis

Seeing a supported plugin is not enough. ScenarioMesh takes ownership only when it can **prove enough compatibility to preserve the relevant execution semantics**.

"Can be proven" means ScenarioMesh understands the effective selection and execution settings required to reproduce what the Maven plugin would have done. Examples include supported includes/excludes, system properties, JVM arguments, failure behavior, framework selection, and other explicitly supported settings.

Conceptually:

```text
Candidate Maven execution
        |
        v
Can ScenarioMesh identify selected tests?
        |
Can it preserve required JVM/system properties?
        |
Can it preserve failure semantics?
        |
Can it identify a supported framework adapter?
        |
Can it avoid duplicate Maven execution?
        |
        +--> YES -> TAKE OVER this execution scope
        |
        +--> NO  -> PASS THROUGH safely
```

Unknown configuration must not be silently ignored. Correctness is more important than parallelism.

## 7. Step 5 - Configuration resolution

ScenarioMesh resolves one cohesive configuration model. Operational defaults are centralized rather than scattered through schedulers, launchers, adapters, or reporting code.

The current configuration includes areas such as:

```text
enabled
execution adapter / mismatch policy
worker count
worker startup/shutdown timeouts
worker JVM arguments
discovery timeout
report directory
live console logging
per-worker files
configuration/progress output
```

The resolved configuration is shown at startup when configured, so a developer or CI log can answer questions such as "which adapter?", "how many workers?", "which report directory?", and "is live output enabled?".

## 8. Step 6 - Adapter selection

An adapter is a **framework bridge**, not a parser.

ScenarioMesh core understands generic concepts such as:

```text
ScenarioTask
ExecutionResult
ScenarioId
RunId
WorkerId
```

It intentionally does not contain Cucumber-, JUnit-, or TestNG-specific discovery logic.

Adapters translate between a framework's native model and ScenarioMesh's model:

```text
framework-native executable test
        |
        v
framework adapter
        |
        v
ScenarioTask
```

and during execution:

```text
ScenarioTask
        |
        v
matching framework adapter
        |
        v
framework-native execution
```

### Currently implemented adapter families

| Adapter | Native mechanism | Typical use |
| --- | --- | --- |
| JUnit Platform | JUnit Platform Launcher/TestPlan/UniqueId | JUnit Jupiter (JUnit 5) and engines exposed through JUnit Platform, including Cucumber Engine when present |
| Cucumber JUnit 4 | JUnit 4/Cucumber runner and Description semantics | Legacy `@RunWith(Cucumber.class)` repositories, including generated runner structures |
| TestNG | TestNG runtime APIs | TestNG test classes/invocations supported by the current adapter |

Adapter availability is evidence-based. Merely finding a dependency jar does not mean that adapter owns the requested tests.

An explicit configured adapter can be used, but ScenarioMesh validates it according to the configured mismatch policy rather than blindly trusting stale configuration.

## 9. Adapter versus parser

A parser would make ScenarioMesh responsible for understanding source syntax itself:

```text
open .feature
 -> parse Scenario
 -> parse Examples
 -> decide what is executable
```

or:

```text
open Java source
 -> search for @Test
 -> infer runtime behavior
```

ScenarioMesh deliberately does not use that model for framework discovery.

An adapter instead asks the framework/runtime that already owns those semantics:

```text
ScenarioMesh
 -> official/native framework discovery mechanism
 -> framework tells ScenarioMesh what is executable
```

This matters for Scenario Outlines, parameterized tests, dynamic tests, TestNG invocations, filters, engine behavior, and future framework changes. ScenarioMesh should not reimplement Cucumber, JUnit, or TestNG.

## 10. JUnit Platform discovery in simple terms

JUnit 5 is an ecosystem. JUnit Jupiter is the common programming model containing annotations such as `@Test`; JUnit Platform is the lower-level launching/discovery layer that can host multiple engines.

Conceptually:

```text
JUnit Platform
   +-- Jupiter Engine
   +-- Cucumber Engine
   +-- other installed engines
```

The ScenarioMesh JUnit Platform adapter creates a `LauncherDiscoveryRequest` using the applicable selectors/filters and asks the JUnit Platform Launcher to discover tests. The Platform returns a `TestPlan` containing test identifiers and hierarchy. ScenarioMesh converts executable leaves into `ScenarioTask` objects.

ScenarioMesh does not scan Java source looking for `@Test`.

JUnit Platform `UniqueId` is valuable because it provides framework-native execution identity that is stronger than a display name.

## 11. Cucumber JUnit 4 discovery in simple terms

Legacy Cucumber/JUnit 4 does not use the JUnit Platform model in the same way. Repositories commonly expose Cucumber through JUnit 4 runners.

Conceptually:

```text
JUnit 4 runner
 -> Cucumber runner
 -> JUnit Description tree
 -> feature/scenario executable leaves
```

The Cucumber JUnit 4 adapter understands this native runner/Description world and converts discovered executable leaves into ScenarioMesh tasks.

A runner is not assumed to equal one scenario. One runner may expose many scenarios. Conversely, a build-time generator may create many granular runners, including separate generated units for Scenario Outline rows.

## 12. TestNG discovery in simple terms

TestNG has its own runtime model for classes, methods, groups, parameters, data providers, configuration methods, and invocations. The TestNG adapter uses TestNG APIs rather than treating TestNG Java files as text.

Framework terminology is allowed to differ. A Cucumber scenario, a JUnit test method/dynamic test, and a TestNG invocation can all become a ScenarioMesh `ScenarioTask`. `ScenarioTask` is the scheduler's neutral name for an executable unit; it does not imply that every framework literally contains a Gherkin scenario.

## 13. Step 7 - Execution identity

Display names are for people. Execution identities are for correctness.

Two legitimate executable tests can have the same display name. Examples include Scenario Outline rows, parameterized tests, data-provider invocations, or generated runners.

Therefore ScenarioMesh must not deduplicate by scenario/test name.

The adapter owns the strongest framework-native identity available:

```text
JUnit Platform
 -> UniqueId where available

Cucumber JUnit 4
 -> runner/native leaf selector plus source evidence where available

TestNG
 -> class/method plus invocation/data identity where available
```

For a generated JUnit 4 structure, two different runners exposing the same human-readable scenario can still be distinct execution units. For a single runner exposing two scenarios, runner class alone is insufficient, so the native leaf selector is also required.

If an adapter cannot safely distinguish two executable units after using available native evidence, ScenarioMesh must fail discovery clearly rather than invent uniqueness or silently discard work.

## 14. Step 8 - ScenarioTask creation

After native discovery, the adapter produces immutable/strongly typed ScenarioMesh tasks. Conceptually a task can contain:

```text
execution identity
display name
framework/adapter identity
native selector
source URI/location when available
tags/metadata when available
estimated duration when available
resource requirements when available in future
```

The scheduler receives tasks. It does not receive Cucumber `Description`, JUnit `TestIdentifier`, or TestNG internals. This is the separation that allows new adapters without rewriting scheduling.

## 15. Step 9 - Worker JVM creation

ScenarioMesh workers are separate Java processes, not merely threads in the Maven JVM.

```text
Maven/ScenarioMesh coordinator JVM
        |
        +--> Worker JVM 1
        +--> Worker JVM 2
        +--> Worker JVM 3
        +--> Worker JVM 4
```

The launcher builds the worker process from the effective test runtime classpath and ScenarioMesh worker runtime. Users should not manually assemble a giant classpath.

Each worker gets its own Java heap and therefore its own copies of static fields, singleton objects, in-memory caches, and framework state.

For example, with thread-only parallelism:

```text
Thread A ----\
Thread B -----+--> same static WebDriver field in one JVM
Thread C ----/
```

With process isolation:

```text
Worker JVM 1 -> its own static WebDriver field
Worker JVM 2 -> its own static WebDriver field
Worker JVM 3 -> its own static WebDriver field
```

This protects legacy in-process state from cross-worker overwrites. It does **not** isolate external resources such as the same test account, database record, environment capacity, or remote service; resource-aware scheduling is a separate concern.

## 16. Step 10 - Worker/coordinator communication

Workers communicate with the coordinator through explicit, versioned protocol messages rather than by sharing Java objects or heap state.

Conceptually:

```text
Coordinator                         Worker
    |                                 |
    | <----------- HELLO -------------|
    | <----------- READY -------------|
    |                                 |
    | ------------ RUN -------------->|
    |                                 | executes native test
    | <----------- RESULT ------------|
    | <----------- READY -------------|
    |                                 |
    | ------------ STOP ------------->|
    | <------------ ACK --------------|
```

Protocol/control messages are separate from human-readable stdout/stderr. This prevents test logging from corrupting coordinator communication.

Worker output can independently be streamed to the Maven/Jenkins console and/or persisted to per-worker files according to configuration.

## 17. Step 11 - Scheduling

### Current scheduling strategy: dynamic FIFO

The current MVP supports FIFO dynamic assignment.

```text
queue: Task1 Task2 Task3 Task4 Task5 Task6 ...

worker-1 READY -> Task1
worker-2 READY -> Task2
worker-3 READY -> Task3
worker-4 READY -> Task4

worker-2 finishes first
worker-2 READY -> Task5 immediately
```

Workers do not choose arbitrary tests. The coordinator/scheduler owns assignment.

This is dynamic rather than fixed static sharding: a fast worker can consume more tasks instead of waiting for the slowest member of a preassigned shard.

The scheduling abstraction is intentionally strategy-based so duration-aware/LPT, resource-aware, affinity, or other strategies can be added later without putting infrastructure or framework logic inside the algorithm.

## 18. Step 12 - Execution inside a worker

The worker receives a generic task and routes it to the matching execution adapter:

```text
ScenarioTask
 -> worker runtime
 -> matching adapter
 -> framework-native selector
 -> JUnit/Cucumber/TestNG
 -> existing hooks/listeners/framework code
 -> Selenium/browser/application code when applicable
```

ScenarioMesh does not replace Selenium or WebDriver. Existing framework lifecycle remains owned by the test framework/adapter path.

## 19. Step 13 - Results and progress

A worker returns a structured result containing information such as task identity, status, duration, worker identity, timestamps, and failure metadata.

ScenarioMesh distinguishes test failures from infrastructure failures. A failed assertion is not the same thing as a crashed worker or broken execution transport.

After returning a terminal result, a healthy worker becomes READY and receives the next eligible task.

Progress output can show total/completed/failed/queued/busy counts and the worker currently handling each task.

## 20. Step 14 - Shutdown and reporting

When all tasks reach terminal states, ScenarioMesh stops assignment, performs bounded worker shutdown, flushes results, and generates reports.

Current reporting includes human-readable HTML plus machine-readable outputs. The report distinguishes:

- Passed tests
- Test failures
- Infrastructure errors
- Actual elapsed time
- Estimated serial execution time based on summed observed scenario durations
- Estimated parallel time saved
- Estimated speedup versus serial
- Per-worker execution work

The serial comparison is an estimate based on observed scenario durations; ScenarioMesh does not silently execute the suite a second time sequentially.

## 21. Step 15 - Maven outcome

ScenarioMesh maps the aggregate outcome back to Maven semantics.

```text
all definitive tests passed
 -> BUILD SUCCESS

one or more definitive test failures
 -> BUILD FAILURE unless supported original plugin semantics explicitly ignore test failures

infrastructure/discovery/configuration failure
 -> BUILD FAILURE with an infrastructure reason
```

Infrastructure failures must never be silently reported as successful tests.

## 22. Current capability map at each stage

| Stage | Current support | Extension direction |
| --- | --- | --- |
| Build tool | Maven | Gradle can be added through separate integration |
| Maven executors | Supported Surefire/Failsafe executions when semantics are understood | Broader plugin configuration coverage |
| Framework discovery | JUnit Platform, Cucumber JUnit 4, TestNG adapter families | Additional engines/adapters |
| Cucumber syntax | Delegated to Cucumber/framework; no ScenarioMesh Gherkin parser | Continue native discovery |
| Execution identity | Adapter-owned native identity | Stronger native selectors as frameworks expose them |
| Worker type | Local isolated JVM process | Docker/remote/Kubernetes launchers |
| Worker count | Configurable | Capacity/autoscaling policies later |
| Scheduler | Dynamic FIFO | Duration-aware/LPT, resource-aware, affinity |
| IPC | Explicit local worker protocol | Additional transports behind abstraction |
| Logs | Live console and/or per-worker files | Structured event sinks/telemetry |
| Reports | HTML + machine-readable outputs | Richer trends/history/dashboard |
| CI | Noninteractive Maven execution/local child JVM model | Provider-specific integrations only where useful |

## 23. Safety boundaries

ScenarioMesh must pass through or fail clearly rather than guess when it cannot preserve correctness. Examples include unsupported Maven plugin semantics, ambiguous execution ownership, an unsupported/proprietary framework without an adapter, or native executable units that cannot be uniquely identified.

ScenarioMesh should never silently skip a test, silently execute the same Maven scope twice, or call an infrastructure failure a passing test.

## 24. Short explanation for another engineer

> Maven still owns the build and performs normal generation and compilation. At a supported Surefire/Failsafe execution boundary, ScenarioMesh first verifies that it can preserve that execution's semantics. A framework adapter then uses the framework's native APIs to discover executable tests; it does not parse Gherkin or Java source itself. Those tests become generic ScenarioTasks. ScenarioMesh starts isolated worker JVMs, workers connect to the coordinator through a versioned protocol, and the FIFO scheduler dynamically gives the next task to the next ready worker. Each worker executes through the appropriate framework adapter, returns a structured result, and becomes available for more work. When all work is complete, ScenarioMesh shuts workers down, aggregates reports, and returns the correct Maven result.