# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for existing Java test repositories. The current MVP is intentionally small: it proves the complete path from a repository's normal Maven command to automatic discovery, isolated worker JVMs, dynamic task assignment, result aggregation, and a unified report.

## MVP support

| Repository style | MVP status |
|---|---|
| Maven + native JUnit 5 / JUnit Platform | Supported |
| Maven + Cucumber JUnit Platform engine | Supported |
| Maven + Cucumber JUnit 4 runner | Supported |
| Maven + generated Cucumber JUnit 4 runners / generated feature copies | Supported when each generated runner exposes executable JUnit leaves |
| Maven Failsafe `integration-test` / `verify` execution | Supported for the documented compatible subset; unsupported semantics pass through |
| Maven + standard method-level TestNG `@Test` tests | Supported |
| Generic JUnit 4 without Cucumber | Pass-through to normal Maven |
| TestNG XML-suite-only / factory-heavy discovery | Pass-through when detectable from Maven configuration; otherwise not yet supported |
| Gradle | Not yet supported |

ScenarioMesh does **not** parse Gherkin and does not replace Selenium, Cucumber, JUnit, TestNG, Surefire, or Failsafe. Target-project dependencies and test resources are loaded in worker JVMs from the Maven test runtime classpath.

## What happens during a normal Maven command

```text
normal Maven command (`test`, `verify`, `install`, ...)
        ↓
ScenarioMesh Maven Core Extension
        ↓
shared config resolution
        ↓
actual Maven lifecycle / participating test-executor analysis
   ├── cannot guarantee compatibility → leave normal Maven execution unchanged
   └── compatible → inject ScenarioMesh at the owned test phase and suppress only the replaced executor
        ↓
prepare target test runtime classpath
        ↓
probe registered framework adapters
        ↓
AUTO: exactly one adapter owns executable tests?
   ├── yes → select it
   └── no  → fail clearly rather than guess
        ↓
ScenarioTask list
        ↓
start isolated JVM workers (4 by default)
        ↓
dynamic FIFO assignment: next free worker gets next task
        ↓
collect typed results
        ↓
JSON + JUnit XML + HTML report
        ↓
correct Maven success/failure
```

The compatibility gate is intentionally conservative. ScenarioMesh prefers a false negative (normal Maven runs) over taking ownership of a project whose existing test semantics it cannot reproduce safely. Compatibility is based on the Maven execution that actually participates in the requested lifecycle, not merely on whether Surefire or Failsafe appears in the POM.

For Failsafe, ScenarioMesh currently translates compatible `argLine`, `systemPropertyVariables`, `testFailureIgnore`, class includes/excludes, and a zero `rerunFailingTestsCount`. Positive Failsafe retry counts still pass through until exact retry semantics are implemented, preventing ScenarioMesh from accidentally duplicating reruns. Unknown or unsupported Maven test-executor settings remain pass-through material rather than being ignored.

The workers exist only for the current Maven run in the MVP. Persistent/recyclable workers are a later milestone.

## Generated Cucumber JUnit 4 runners

Some Maven/Cucumber repositories expand `Scenario Outline` example rows or other generated execution units into separate feature resources and JUnit 4 runner classes. Those generated units may legitimately have the **same feature name and scenario display name** while containing different substituted values.

ScenarioMesh therefore separates human-readable identity from execution identity:

```text
Display identity
  feature/scenario description
  → reporting and diagnostics only

Execution identity
  framework adapter + runner class + JUnit leaf selector
  → task uniqueness and execution
```

ScenarioMesh does **not** deduplicate Cucumber JUnit 4 work by scenario name. If Maven/Failsafe exposes two distinct generated runners, their executable leaves remain distinct ScenarioMesh tasks even when their display text is identical. This preserves row-wise Scenario Outline expansion and other generator-specific execution units without hard-coding example-column names, generated filename formats, company conventions, or data values.

A single runner may expose multiple executable leaves; ScenarioMesh schedules each leaf independently. Conversely, `one runner = one scenario` is never assumed.

## One-time installation for a target repository

First install or publish ScenarioMesh itself. During local development:

```bash
mvn clean install
```

Then add `.mvn/extensions.xml` to the target repository:

```xml
<extensions>
  <extension>
    <groupId>io.scenariomesh</groupId>
    <artifactId>scenariomesh-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

No test-source or target `pom.xml` dependency changes are required. After that, keep using the repository's existing lifecycle command, for example:

```bash
mvn test
```

```bash
mvn clean compile verify
```

or with framework-native filters:

```bash
mvn test -Dcucumber.filter.tags="@Regression"
```

Command-line `-D` properties are forwarded to discovery and worker JVMs so framework-native properties such as Cucumber tag filters remain available. If a Maven selector or executor setting is not yet reproduced safely, the compatibility gate leaves the run with normal Maven instead of changing the selected test set.

## Configuration: automatic by default, explicit when useful

**No config file is required.** The default adapter mode is `auto`, worker count is `4`, and ScenarioMesh probes the target runtime to determine which registered adapter actually discovers executable tests.

A repository can optionally add `scenariomesh.yml` when it needs stable team-owned overrides or when the team wants to state which adapter is expected:

```yaml
scenariomesh:
  configVersion: 1
  enabled: true

  execution:
    adapter: auto
    adapterMismatchPolicy: fail

  workers:
    count: 4

  reporting:
    directory: target/scenariomesh
```

`execution.adapter` supports `auto`, `junit-platform`, `cucumber-junit4`, `testng`, and future adapter IDs registered by newer ScenarioMesh runtimes. Explicit adapter configuration is treated as a **user assertion** and is still validated against runtime discovery evidence; it is not a switch that disables safety checks.

If `auto` finds more than one adapter with executable tests, ScenarioMesh refuses to guess. A team that intentionally has multiple framework libraries can set the correct adapter explicitly. If the configured adapter does not apply, `adapterMismatchPolicy: fail` stops safely; `use-detected` may use a different adapter only when exactly one alternative is uniquely detected.

See [`docs/configuration.md`](docs/configuration.md) for why the file exists, every switch, all current options, defaults, environment variables, precedence, validation rules, and examples. A fully commented template is available as [`scenariomesh.example.yml`](scenariomesh.example.yml).

Configuration precedence is centralized and consistent:

```text
Maven -D property
      ↓
environment variable
      ↓
scenariomesh.yml / scenariomesh.yaml
      ↓
documented ScenarioMesh defaults
```

## Workers

Default worker count is **4** and is owned by `ScenarioMeshConfig`, not duplicated in runtime code.

Canonical override:

```bash
mvn test -Dscenariomesh.workers.count=2
```

The earlier MVP property `-Dscenariomesh.workers=2` remains a backward-compatible alias.

Workers are separate JVM processes. This provides isolation for legacy frameworks that use static WebDriver fields, mutable singletons, global caches, or other process-local state.

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
        └── logs/
            ├── worker-1.log
            ├── worker-2.log
            ├── worker-3.log
            └── worker-4.log
```

When the compatibility gate chooses pass-through, ScenarioMesh does not take ownership of the project's test executor and does not create a ScenarioMesh report; the repository's normal Maven behavior remains in control.

Override the report directory with `-Dscenariomesh.reporting.directory=...`.

## Disable ScenarioMesh

```bash
mvn test -Dscenariomesh.enabled=false
```

or in `scenariomesh.yml`:

```yaml
scenariomesh:
  configVersion: 1
  enabled: false
```

When disabled, the extension does not suppress Maven's normal test execution.

## MVP architecture

Framework code is isolated behind `ScenarioAdapter`. JUnit Platform, Cucumber JUnit 4, and TestNG adapters emit the same `ScenarioTask` type. The scheduler and worker coordinator have no Cucumber/Selenium/TestNG imports.

Worker control uses a versioned JSON protocol over a dynamically allocated loopback TCP port. Test stdout/stderr is redirected to per-worker files, so target logging cannot corrupt the control protocol.

See `docs/architecture.md`, `docs/mvp.md`, and `docs/configuration.md`.
