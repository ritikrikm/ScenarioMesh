# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for existing Java test repositories. The current MVP is intentionally small: it proves the complete path from a repository's normal Maven command to automatic discovery, isolated worker JVMs, dynamic task assignment, result aggregation, and a unified report.

## MVP support

| Repository style | MVP status |
|---|---|
| Maven + native JUnit 5 / JUnit Platform | Supported |
| Maven + Cucumber JUnit Platform engine | Supported |
| Maven + Cucumber JUnit 4 runner | Supported |
| Maven + standard method-level TestNG `@Test` tests | Supported |
| Generic JUnit 4 without Cucumber | Pass-through to normal Maven |
| TestNG XML-suite-only / factory-heavy discovery | Pass-through when detectable from Maven configuration; otherwise not yet supported |
| Gradle | Not yet supported |

ScenarioMesh does **not** parse Gherkin and does not replace Selenium, Cucumber, JUnit, or TestNG. Target-project dependencies and test resources are loaded in worker JVMs from the Maven test runtime classpath.

## What happens during `mvn test`

```text
normal Maven command
        ↓
ScenarioMesh Maven Core Extension
        ↓
shared config resolution
        ↓
Maven compatibility gate
   ├── cannot guarantee MVP compatibility → leave Maven/Surefire unchanged
   └── compatible → inject ScenarioMesh test goal + suppress normal Surefire execution
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

The compatibility gate is intentionally conservative. ScenarioMesh prefers a false negative (normal Maven runs) over taking ownership of a project whose existing test semantics it cannot reproduce safely. It currently passes through when it detects unsupported or compatibility-sensitive conditions such as generic JUnit 4, Failsafe integration-test configuration, custom Surefire providers/executions, Surefire selection/classpath/runtime overrides, or `-Dtest`/`-Dit.test` selectors that ScenarioMesh discovery does not yet reproduce.

The workers exist only for the current Maven run in the MVP. Persistent/recyclable workers are a later milestone.

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

No test-source or target `pom.xml` dependency changes are required. After that, keep using the repository's existing lifecycle command:

```bash
mvn test
```

or, for example:

```bash
mvn test -Dcucumber.filter.tags="@Regression"
```

Command-line `-D` properties are forwarded to discovery and worker JVMs so framework-native properties such as Cucumber tag filters remain available. If a Maven/Surefire selector is not yet reproduced by ScenarioMesh (for example `-Dtest=...`), the compatibility gate leaves the run with normal Maven instead of changing the selected test set.

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

Workers are separate JVM processes. This provides isolation for legacy frameworks that use static WebDriver fields, mutable singletons, global caches, or other process-local state. Child JVMs enable Java assertions to match Maven Surefire's default assertion semantics; projects that explicitly override Surefire assertion configuration are conservatively passed through for now.

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
        ├── discovered-scenarios.json   # includes adapter evidence
        ├── discovery.log
        └── logs/
            ├── worker-1.log
            ├── worker-2.log
            ├── worker-3.log
            └── worker-4.log
```

When the compatibility gate chooses pass-through, ScenarioMesh does not inject its test goal, does not set `skipTests`, and does not create a ScenarioMesh report; the repository's normal Maven/Surefire behavior remains in control.

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
