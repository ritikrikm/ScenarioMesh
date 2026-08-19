# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for existing Java test repositories. Its product goal is simple: install it once in a supported Maven repository and keep using the repository's normal Maven command. ScenarioMesh sits underneath the test suite, discovers the work the framework would execute, starts isolated worker JVMs, dynamically assigns executable tests, collects results, and returns the correct Maven outcome.

ScenarioMesh is **not** a Selenium framework, a WebDriver proxy, a Gherkin parser, a replacement for Cucumber/JUnit/TestNG, or a shell wrapper around Maven.

## Current MVP support

| Repository style | MVP status |
|---|---|
| Maven + native JUnit 5 / JUnit Platform | Supported |
| Maven + Cucumber using the JUnit Platform engine | Supported through the JUnit Platform adapter |
| Maven + Cucumber JUnit 4 runner | Supported |
| Maven + generated Cucumber JUnit 4 runners / generated feature copies | Supported when each generated runner exposes executable JUnit leaves |
| Maven Failsafe `integration-test` / `verify` execution | Supported for the documented compatible subset; unsupported semantics pass through |
| Maven + standard method-level TestNG `@Test` tests | Supported |
| Generic JUnit 4 without Cucumber | Pass-through to normal Maven |
| TestNG XML-suite-only / factory-heavy discovery | Pass-through when detectable from Maven configuration; otherwise not yet supported |
| Gradle | Not yet supported |

Target-project libraries such as Selenium, REST Assured, Jackson, internal company libraries, listeners and resources are loaded from Maven's resolved test runtime classpath. ScenarioMesh does not hard-code them.

---

## The complete runtime flow

A normal repository command can be `mvn test`, `mvn verify`, `mvn clean install`, `mvn clean compile verify`, or another lifecycle command. ScenarioMesh does not require the command to be known ahead of time.

```text
normal Maven command
        ↓
.mvn/extensions.xml loads ScenarioMesh Maven Core Extension
        ↓
ScenarioMesh inspects the requested Maven lifecycle
        ↓
Which test executor actually participates in THIS invocation?
        ↓
Surefire / Failsafe / unsupported execution semantics
        ↓
Compatibility gate
   ├── cannot reproduce semantics safely
   │       ↓
   │   PASS-THROUGH
   │   normal Maven remains in control
   │
   └── compatible
           ↓
       inject ScenarioMesh at the phase owned by that executor
           ↓
       suppress only the Maven test execution ScenarioMesh replaces
           ↓
       preserve compilation, generation, profiles and properties
           ↓
       build target test runtime classpath
           ↓
       framework-native adapter discovery
           ↓
       ScenarioTask list
           ↓
       start isolated worker JVMs
           ↓
       dynamic FIFO assignment
           ↓
       collect terminal results
           ↓
       write JSON / JUnit XML / HTML reports
           ↓
       preserve Maven success/failure semantics
```

Correctness is more important than parallelism. If ScenarioMesh cannot prove that it can reproduce the participating Maven executor safely, it does not guess.

---

## How Maven takeover works

### Core Extension

A target repository activates ScenarioMesh through `.mvn/extensions.xml`. The extension participates in Maven itself; ScenarioMesh does not replace the `mvn` executable and does not require a wrapper script.

The extension sees the actual requested lifecycle. For example:

```text
mvn test
→ lifecycle ends at test
→ Failsafe normally bound to integration-test is irrelevant to this invocation
```

while:

```text
mvn clean compile verify
→ lifecycle reaches integration-test and verify
→ a Failsafe integration-test execution participates
→ ScenarioMesh must reproduce that Failsafe execution before takeover is allowed
```

The important rule is:

```text
"plugin exists in the POM"
        ≠
"plugin participates in this Maven invocation"
```

ScenarioMesh reasons about the execution that actually participates.

### Surefire

For a compatible Surefire-owned `test` lifecycle, ScenarioMesh takes ownership at the test phase and prevents the replaced Surefire execution from executing the same tests again.

### Failsafe

For a compatible Failsafe-owned integration-test lifecycle, ScenarioMesh takes ownership at `integration-test`, while Maven's surrounding lifecycle remains intact:

```text
generate sources/resources
        ↓
compile / test-compile
        ↓
package / pre-integration-test
        ↓
ScenarioMesh execution at integration-test
        ↓
post-integration-test
        ↓
verify
```

This is important because enterprise repositories may prepare environments, generate runners, archive reports, or perform cleanup around the integration-test lifecycle.

ScenarioMesh currently translates the compatible Failsafe subset including class includes/excludes, compatible `argLine`, `systemPropertyVariables`, `testFailureIgnore`, and `rerunFailingTestsCount=0`. Positive retry counts and unknown execution-affecting settings pass through until exact equivalent behavior exists. ScenarioMesh never silently drops such settings.

---

## Discovery: how ScenarioMesh knows what to run

ScenarioMesh does **not** parse arbitrary `.feature` files itself.

Discovery is performed by framework adapters using the framework's execution model. All adapters return the same framework-neutral domain model:

```text
framework discovery
        ↓
ScenarioTask
        ↓
coordinator / scheduler / workers
```

The coordinator therefore does not know whether a task came from Cucumber, JUnit or TestNG.

### Adapter registry

The current runtime ships three registered adapter implementations:

```text
AdapterRegistry
├── junit-platform
├── cucumber-junit4
└── testng
```

They are **combined only through the common `ScenarioAdapter` interface and registry**. Their framework-specific discovery/execution logic remains separate. Adding a future adapter should not require Cucumber/TestNG logic to be added to the coordinator or scheduler.

### 1. JUnit Platform adapter

Adapter id:

```text
junit-platform
```

It supports native JUnit 5 and Cucumber when Cucumber runs as a JUnit Platform engine. Discovery uses the JUnit Platform Launcher/TestPlan model rather than reading Gherkin manually.

Conceptually:

```text
LauncherDiscoveryRequest
        ↓
JUnit Platform Launcher
        ↓
registered test engines
        ↓
TestPlan
        ↓
executable test identifiers
        ↓
ScenarioTask
```

If the Cucumber engine is installed, Cucumber scenarios appear through that official engine. If native Jupiter tests are installed, they appear through Jupiter.

### 2. Cucumber JUnit 4 adapter

Adapter id:

```text
cucumber-junit4
```

This adapter finds JUnit 4 classes whose `@RunWith` points to a supported Cucumber runner. It asks JUnit for the runner's `Description` tree and walks executable leaf descriptions.

```text
JUnit 4 runner class
        ↓
Request.aClass(...)
        ↓
JUnit Description tree
        ↓
executable leaves
        ↓
ScenarioTask
```

A runner is a **container**, not automatically one task. One runner can expose multiple executable leaves.

### Generated Cucumber JUnit 4 runners

Some repositories generate one feature resource / runner per Scenario Outline row or other execution unit. Example:

```gherkin
Scenario Outline: Close opportunity
  Given "<user>" logs in
  ...

Examples:
| user |
| BM   |
| PBA  |
```

A generator may produce:

```text
target/parallel/features/...scenario001_run001_IT.feature
→ user = BM

target/parallel/features/...scenario004_run001_IT.feature
→ user = PBA
```

with matching runner classes.

Those generated executions can legitimately have the same feature/scenario display text. ScenarioMesh therefore separates **display identity** from **execution identity**:

```text
Display identity
→ feature/scenario description
→ reports and diagnostics

Execution identity
→ adapter + runner class + JUnit leaf selector
→ uniqueness and actual execution
```

ScenarioMesh does **not** deduplicate JUnit 4 Cucumber work by scenario name. Different generated runners remain different executable tasks even if the text shown to a human is identical. This remains generic because ScenarioMesh does not care whether rows differ by `user`, `stage`, `product`, browser, dataset, or ten example columns.

### 3. TestNG adapter

Adapter id:

```text
testng
```

The MVP supports standard method-level TestNG `@Test` discovery/execution. TestNG XML-suite-only and factory-heavy models are not claimed as supported when ScenarioMesh cannot reproduce them safely.

---

## Automatic adapter selection

Default configuration:

```yaml
execution:
  adapter: auto
```

AUTO does not simply look for dependencies. ScenarioMesh probes the registered adapters using the target project's actual compiled test roots and runtime classpath.

```text
junit-platform probe
cucumber-junit4 probe
testng probe
        ↓
collect evidence
        ↓
exactly one adapter discovers executable tests?
        ├── YES → select it
        └── NO  → do not guess
```

A repository can explicitly state an adapter, but ScenarioMesh still validates that assertion against runtime evidence. See `docs/configuration.md`.

---

## Worker model

Workers are separate Java processes, not Java threads inside the target test JVM.

```text
Coordinator JVM
   │
   ├── worker-1 JVM
   ├── worker-2 JVM
   ├── worker-3 JVM
   └── worker-4 JVM
```

Each worker has its own heap, static fields, singletons and framework state. This is the main compatibility advantage for older Selenium frameworks that may contain static WebDriver fields or other global mutable state.

### How workers are created

The Maven integration gives ScenarioMesh the target project's resolved test runtime classpath. ScenarioMesh launches child JVMs using Java `ProcessBuilder` and the current Java runtime rather than hard-coded operating-system commands or classpath separators.

Each worker receives:

- the target project's test runtime classpath;
- ScenarioMesh worker runtime classes;
- configured worker JVM arguments;
- compatible executor JVM arguments inherited from Surefire/Failsafe;
- user/system properties needed by the test runtime;
- a dynamically allocated loopback control endpoint;
- a worker id.

No user-maintained giant `-cp` command is required.

### Worker control protocol

Worker control is separate from test logging. The coordinator opens a dynamically allocated loopback TCP endpoint and workers exchange versioned JSON protocol messages such as HELLO, RUN, RESULT and STOP.

Target framework stdout/stderr is **never used as the control protocol**, so logging from Selenium/Cucumber/application code cannot corrupt ScenarioMesh control messages.

### Scheduling

Current MVP scheduler:

```text
FIFO + dynamic assignment
```

All tasks enter the scheduler. Each worker requests/receives the next available task after finishing its current task.

```text
32 tasks
        ↓
worker-1 gets task 1
worker-2 gets task 2
worker-3 gets task 3
worker-4 gets task 4
        ↓
worker-2 finishes first
        ↓
worker-2 immediately gets task 5
```

This is not static "8 tasks per worker" partitioning. Faster workers naturally consume more tasks.

---

## Runtime observability and logs

ScenarioMesh now exposes both target-framework logs and its own operational state.

Default logging configuration:

```yaml
scenariomesh:
  configVersion: 1
  logging:
    liveConsole: true
    workerFiles: true
    showConfiguration: true
    showProgress: true
```

### Startup configuration summary

With `showConfiguration: true`, the Maven console shows the resolved runtime plan before execution, including:

```text
ScenarioMesh version
Project
Requested Maven goals
Maven executor takeover (Surefire/Failsafe)
Owned lifecycle phase
Adapter intent
Adapter mismatch policy
Worker count
Scheduler
Live-console setting
Worker-file setting
Progress setting
Report directory
Config source
```

Secrets and target project system-property values are intentionally not dumped.

### Live target-framework logs

With `liveConsole: true`, stdout/stderr produced inside each worker is mirrored to Maven/Jenkins while retaining the target framework's existing message content:

```text
[ScenarioMesh][worker-1] INFO com.example.steps.LoginSteps - opening login page
[ScenarioMesh][worker-3] INFO com.example.steps.OpportunitySteps - opportunity created
```

Parallel logs can naturally interleave; the worker prefix makes ownership visible.

### Per-worker files

With `workerFiles: true`, the same worker stream is saved separately as raw output:

```text
target/scenariomesh/runs/<run-id>/logs/worker-1.log
target/scenariomesh/runs/<run-id>/logs/worker-2.log
...
```

`liveConsole` and `workerFiles` are independent booleans. Both may be on, either may be on alone, or both may be off. Even when both are off, ScenarioMesh still drains the child process stream so a verbose worker cannot deadlock on a full stdout buffer.

### Progress

With `showProgress: true`, ScenarioMesh prints worker lifecycle and queue state:

```text
[ScenarioMesh] Starting worker-1 (pid=...)
[ScenarioMesh] worker-1 READY
[ScenarioMesh] Scheduler FIFO loaded 32 task(s); 4 worker(s) ready.
[ScenarioMesh] worker-2 RUN Close opportunity | completed=3/32 busy=4 queued=25
[ScenarioMesh] worker-3 PASSED Update lead | completed=4/32 failed=0 busy=3 queued=25
```

This makes it visible which worker is active and how much of the run remains.

---

## Reports

When ScenarioMesh owns a compatible run:

```text
target/scenariomesh/
├── report.html
├── summary.json
├── junit.xml
└── runs/
    └── <run-id>/
        ├── report.html
        ├── summary.json
        ├── junit.xml
        ├── discovered-scenarios.json
        ├── discovery.log
        └── logs/                    # present when workerFiles=true
            ├── worker-1.log
            ├── worker-2.log
            ├── worker-3.log
            └── worker-4.log
```

When the compatibility gate chooses pass-through, ScenarioMesh does not run workers and normal Maven remains responsible for test reports.

---

## One-time installation in a target repository

Install/publish ScenarioMesh itself. During local development:

```bash
mvn clean install
```

Add `.mvn/extensions.xml` to the target repository:

```xml
<extensions>
  <extension>
    <groupId>io.scenariomesh</groupId>
    <artifactId>scenariomesh-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Then keep using the repository's normal Maven command:

```bash
mvn test
```

or:

```bash
mvn clean compile verify
```

or with framework-native properties:

```bash
mvn test -Dcucumber.filter.tags="@Regression"
```

Command-line properties are forwarded to discovery and worker JVMs where required. ScenarioMesh does not require feature files, step definitions, WebDriver implementations or runner source to be rewritten.

---

## Configuration

No `scenariomesh.yml` file is required. Defaults are centralized in `ScenarioMeshConfig`.

Example:

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
    shutdownTimeout: PT10S
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

Configuration precedence is centralized:

```text
Maven -D property
      ↓
environment variable
      ↓
scenariomesh.yml / scenariomesh.yaml
      ↓
documented defaults
```

See [`docs/configuration.md`](docs/configuration.md) and [`scenariomesh.example.yml`](scenariomesh.example.yml) for every switch and rationale.

---

## Disable ScenarioMesh

```bash
mvn test -Dscenariomesh.enabled=false
```

or:

```yaml
scenariomesh:
  configVersion: 1
  enabled: false
```

When disabled, ScenarioMesh does not suppress the repository's normal Maven test execution.

---

## Why the architecture is separated this way

```text
Maven integration
→ determines whether/where ScenarioMesh may take ownership

Adapter layer
→ understands framework-native discovery/execution

Core domain
→ ScenarioTask / ExecutionResult and framework-neutral contracts

Scheduler
→ decides task assignment only

Coordinator
→ owns run orchestration and worker processes

Worker runtime
→ executes one selected task inside an isolated JVM

Reporting
→ aggregates framework-neutral results
```

This separation is intentional. Maven-specific behavior does not belong in the scheduler. Cucumber-specific behavior does not belong in the coordinator. Selenium-specific behavior does not belong in ScenarioMesh at all.

The MVP uses ephemeral per-run workers. Persistent/recyclable worker pools, duration-aware scheduling, resource leases, daemon lifecycle, remote launchers and broader retry semantics remain future milestones and should build on these boundaries rather than replacing them.

See `docs/architecture.md`, `docs/mvp.md`, `docs/configuration.md`, and `docs/adapter-development.md`.
