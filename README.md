# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for existing Java test automation repositories. Its primary product rule is simple:

> **Prove compatibility, then take ownership. If compatibility cannot be proven, leave native Maven execution alone.**

For supported Maven repositories, teams keep using normal commands such as `mvn test` and `mvn verify`. ScenarioMesh participates inside Maven, discovers framework-native executable work, starts isolated worker JVMs, dynamically schedules compatible work, preserves Maven success/failure semantics, and writes standard reports.

ScenarioMesh is not a Selenium framework, WebDriver proxy, Gherkin parser, replacement for JUnit/Cucumber/TestNG, or shell wrapper around Maven.

## Current support

| Repository style | Status |
|---|---|
| Maven + JUnit 5 / JUnit Platform | Supported |
| Maven + JUnit 6 / JUnit Platform | Supported; launcher is aligned to the target Maven dependency graph |
| Maven + Cucumber JUnit Platform 7.x engine | Supported through the JUnit Platform adapter; unqualified future major lines pass through |
| Maven + Cucumber JUnit 4 runner | Supported |
| Generated Cucumber JUnit 4 runners exposing executable leaves | Supported |
| Compatible Maven Surefire `test` execution | Supported |
| Compatible Maven Failsafe `integration-test` / `verify` execution | Supported; unsupported semantics pass through |
| Standard method-level TestNG `@Test` | Supported |
| Generic JUnit 4 without Cucumber | Supported through JUnit Platform/Vintage when present, and through the direct legacy JUnit 4 adapter for proven compatible Surefire JUnit4/JUnit47 execution |
| TestNG `suiteXmlFiles` | Supported as atomic TestNG lifecycle scopes; individual outcomes are materialized after native TestNG execution |
| Factory-heavy TestNG discovered without a suite XML | Pass-through when ScenarioMesh cannot prove equivalent isolated semantics |
| Gradle | Not supported yet |

The current proven Cucumber JUnit Platform ownership contract is the Cucumber 7.x line. Cucumber 8.x is not claimed yet and remains native Maven until separately qualified.

Target-project libraries such as Selenium, REST Assured, Jackson, listeners, resources, and internal libraries are loaded from Maven's resolved test runtime classpath rather than hard-coded into ScenarioMesh.

## Runtime flow

```text
normal Maven command
        ↓
ScenarioMesh Maven Core Extension inspects the requested lifecycle
        ↓
Surefire / Failsafe execution that actually participates
        ↓
compatibility + runtime ownership preflight
   ├── cannot prove equivalence
   │       ↓
   │   native Maven pass-through
   │
   └── ownership proven
           ↓
       suppress only the Maven test execution being replaced
           ↓
       preserve compilation / generation / profiles / properties / JVM selection
           ↓
       framework-native discovery
           ↓
       lifecycle-safe work units
           ↓
       isolated local or authenticated remote worker JVMs
           ↓
       capability-aware dynamic scheduling
           ↓
       lease-authoritative results
           ↓
       JSON / JUnit XML / Surefire-style XML / HTML / artifact references
           ↓
       original Maven lifecycle continues
```

Correctness is more important than parallelism. Unknown or unsupported execution-affecting Maven settings do not get silently dropped.

## Adapters

ScenarioMesh keeps framework-specific behavior behind `ScenarioAdapter` implementations:

```text
junit-platform
junit4-direct
cucumber-junit4
testng
```

JUnit Platform discovery uses framework-native launcher/test-plan identities. Cucumber JUnit 4 uses JUnit runner/`Description` leaves. TestNG uses its own method-level execution model. ScenarioMesh does not parse arbitrary `.feature` files itself.

Adapters also expose machine-verifiable capabilities. Remote workers advertise supported adapter IDs and JUnit Platform engine IDs, and a worker receives only tasks it can actually execute.

## Worker isolation

Workers are separate Java processes rather than threads inside one shared test JVM:

```text
Coordinator JVM
   ├── worker-1 JVM
   ├── worker-2 JVM
   ├── worker-3 JVM
   └── worker-4 JVM
```

This isolates heap/static/singleton/framework state and is especially useful for older automation frameworks with global mutable state.

The Maven integration preserves the selected test JVM, including supported Surefire/Failsafe JVM and toolchain configuration. Worker processes receive the target project's resolved runtime classpath and compatible execution properties. Surefire's documented `<properties><configurationParameters>` values are parsed with Java `Properties` syntax and forwarded to JUnit Platform; unknown provider properties remain pass-through.

## Scheduling

Default scheduling is history-aware longest-processing-time-first (`history-lpt`) with deterministic FIFO behavior for cold tasks. This reduces long-tail idle time without changing test identities or lifecycle ownership.

```yaml
scenariomesh:
  configVersion: 1
  scheduling:
    strategy: history-lpt
```

Strict FIFO is also available:

```yaml
scheduling:
  strategy: fifo
```

With FIFO, historical duration metadata is deliberately excluded from scheduling decisions. ScenarioMesh still records durations so a later switch back to `history-lpt` has useful history.

Lifecycle affinity always applies. In distributed mode, worker adapter/engine compatibility also constrains eligibility. See [`docs/scheduling.md`](docs/scheduling.md).

## Distributed / Jenkins execution

Jenkins remains responsible for nodes, workspaces, labels, and executor allocation. ScenarioMesh consumes worker processes inside that already-allocated capacity.

A remote worker process currently represents one execution lane. Workers authenticate with a ScenarioMesh registration token; non-loopback transport requires TLS, with mutual TLS enabled by default. Authentication material is passed to worker JVMs through the environment rather than command-line arguments.

Transparent Maven takeover uses the exact authenticated sessions proven during preflight. ScenarioMesh does not suppress native Maven and then silently replace those workers with unproven connections.

Heterogeneous prepared workers are supported when the worker set collectively proves every required adapter/engine capability. JUnit engine compatibility must exist on the same worker as the `junit-platform` adapter. Runtime dispatch re-checks the exact task capability immediately before issuing its work lease.

See [`docs/jenkins-distributed.md`](docs/jenkins-distributed.md) and [`docs/security.md`](docs/security.md).

## Protocol and worker authority

ScenarioMesh worker control uses versioned JSON messages independent of target-project stdout/stderr.

Current session protocol is v9. Bootstrap HELLO remains v8 so current workers can register with preserved bridge-v8 binaries, then negotiate the highest mutually supported session version.

A result is accepted only for the active authoritative lease. Late, duplicate, stale, or replaced-lease results are rejected.

Supported rolling interoperability is currently tested between a v9 coordinator/worker and the preserved bridge-v8 baseline. Mixed sessions fail closed when no compatible negotiated version exists. Historical arbitrary strict-v8 binaries are not claimed compatible.

## Reports and reporting integrations

An owned run produces built-in reports such as:

```text
target/scenariomesh/
├── report.html
├── summary.json
├── junit.xml
├── artifacts.json
└── runs/
    └── <run-id>/
        ├── report.html
        ├── summary.json
        ├── junit.xml
        ├── events.jsonl
        ├── discovered-scenarios.json
        ├── discovery.log
        └── logs/
            └── worker-*.log        # when workerFiles=true
```

`ReportExporter` is a ServiceLoader SPI for downstream report integrations. `ReportArtifactProvider` can publish safe references to screenshots, traces, logs, videos, or external reports. Local references must stay relative to the report directory; external references must use HTTPS. ScenarioMesh writes `artifacts.json` but does not crawl/copy arbitrary workspace files.

See [`docs/reporting-integrations.md`](docs/reporting-integrations.md).

## Observability and diagnostics

Structured runtime events are written to `events.jsonl` with run/worker/task/work-unit/lease correlation where applicable. Known configured/environment secret values are sanitized before structured logging.

A bounded diagnostics archive can be created with:

```bash
java -jar scenariomesh-cli-<version>.jar diagnostics --root .
```

The archive is allowlist-based. It includes generated ScenarioMesh reports/events plus a sanitized manifest; it does not dump environment variables and does not collect raw worker logs by default.

See [`docs/diagnostics.md`](docs/diagnostics.md).

### Optional OpenTelemetry

Core ScenarioMesh has no mandatory OpenTelemetry dependency. The optional module:

```text
io.scenariomesh:scenariomesh-observability-opentelemetry
```

bridges `RunEventSink` events to low-cardinality OpenTelemetry metrics using only `opentelemetry-api`. SDK/exporter installation and configuration remain the application's responsibility. Without an SDK, the API is a no-op.

See [`docs/opentelemetry.md`](docs/opentelemetry.md).

## Quick start: use ScenarioMesh with an existing Maven test project

This is the recommended development/POC flow for the current `main` build (`0.1.1-SNAPSHOT`). The target repository keeps its existing `pom.xml`, test framework, Selenium/REST Assured libraries, Maven goals, profiles, Jenkins commands, and test code.

### 1. Build and install ScenarioMesh once

Clone ScenarioMesh and build the current `main` branch:

```bash
git clone https://github.com/ritikrikm/ScenarioMesh.git
cd ScenarioMesh
git checkout main
git pull
./mvnw clean install
```

This installs the ScenarioMesh `0.1.1-SNAPSHOT` artifacts into your local Maven repository and builds the CLI jar at:

```text
scenariomesh-cli/target/scenariomesh-cli-0.1.1-SNAPSHOT.jar
```

Java 17+ is required. Maven 3.9.x is the primary supported production line.

### 2. Preview what ScenarioMesh will add to the existing project

Assume the existing automation project is at `/path/to/my-automation-project`.

From the ScenarioMesh checkout:

```bash
java -jar scenariomesh-cli/target/scenariomesh-cli-0.1.1-SNAPSHOT.jar \
  init --dry-run --project /path/to/my-automation-project
```

The dry run prints the exact files that would be created or updated and makes no changes.

### 3. Initialize the existing project

If the dry-run plan is correct:

```bash
java -jar scenariomesh-cli/target/scenariomesh-cli-0.1.1-SNAPSHOT.jar \
  init --project /path/to/my-automation-project
```

`init` is idempotent and performs only the bootstrap work ScenarioMesh needs:

- creates or updates `.mvn/extensions.xml`;
- preserves other Maven Core extensions already present in that file;
- adds `io.scenariomesh:scenariomesh-maven-extension:0.1.1-SNAPSHOT`;
- creates a minimal `scenariomesh.yml` only when neither `scenariomesh.yml` nor `scenariomesh.yaml` already exists;
- refuses to guess if both YAML filenames exist;
- leaves the target project's `pom.xml` and test source unchanged.

The minimal generated configuration is intentionally small:

```yaml
scenariomesh:
  configVersion: 1
```

That is enough for zero-config auto-detection. All other settings use safe defaults.

### 4. Optional: add the recommended local/POC configuration

For an explicit four-worker POC, the target project's `scenariomesh.yml` can be expanded to:

```yaml
scenariomesh:
  configVersion: 1
  enabled: true

  execution:
    adapter: auto
    adapterMismatchPolicy: fail

  scheduling:
    strategy: history-lpt

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

For a first run, keep `execution.adapter: auto`. ScenarioMesh will inspect the compiled test runtime and choose a framework adapter only when ownership is unambiguous.

Worker count can be changed without editing YAML:

```bash
mvn test -Dscenariomesh.workers.count=8
```

### 5. Run a compatibility check before the first takeover

From the ScenarioMesh checkout:

```bash
java -jar scenariomesh-cli/target/scenariomesh-cli-0.1.1-SNAPSHOT.jar \
  doctor --deep --root /path/to/my-automation-project
```

The deep doctor command verifies Java/Maven/configuration and runs the same runtime ownership preflight used by transparent Maven takeover.

Typical decisions are:

```text
ScenarioMesh preflight: ownership proven ...
```

or:

```text
ScenarioMesh preflight: native Maven pass-through ...
```

Pass-through is a safe result: it means ScenarioMesh could not prove equivalence for that execution and deliberately leaves native Surefire/Failsafe authoritative.

### 6. Keep using the project's existing Maven commands

After initialization, move into the existing automation project and run it exactly as before:

```bash
cd /path/to/my-automation-project

mvn test
mvn verify
mvn clean test
mvn clean verify
mvn clean install
```

You do not invoke a separate ScenarioMesh test command for transparent takeover.

For each Maven invocation ScenarioMesh automatically:

1. inspects only the Surefire/Failsafe executions that participate in the requested lifecycle;
2. reads execution-affecting Maven settings such as selectors, includes/excludes, JVM selection, compatible `argLine`/properties, fork semantics, retries and framework/provider configuration;
3. waits until test compilation is complete;
4. probes the real target test classpath and compiled tests;
5. verifies that a registered adapter can own the framework/runtime semantics;
6. takes ownership only if all required compatibility checks pass;
7. suppresses only the proven native Surefire/Failsafe execution;
8. starts isolated ScenarioMesh worker JVMs and dynamically schedules the owned work;
9. writes ScenarioMesh reports while allowing the rest of the Maven lifecycle to continue normally.

If any required proof fails, ScenarioMesh does not partially execute the suite. Native Maven remains in control.

### 7. Confirm whether ScenarioMesh actually took over

For an owned execution, the Maven/Jenkins log contains:

```text
MAVEN_OWNERSHIP owner=SCENARIOMESH
ScenarioMesh preflight: ownership proven ...
ScenarioMesh: takeover enabled after runtime ownership preflight.
```

An owned run also creates:

```text
target/scenariomesh/
├── report.html
├── summary.json
├── junit.xml
├── artifacts.json
└── runs/
    └── <run-id>/
        ├── events.jsonl
        ├── discovered-scenarios.json
        └── logs/
```

If the log contains:

```text
MAVEN_OWNERSHIP owner=PASS_THROUGH
```

the existing Surefire/Failsafe execution ran normally instead.

### 8. Disable ScenarioMesh instantly when comparing against the baseline

No files need to be removed:

```bash
mvn test -Dscenariomesh.enabled=false
```

This is useful for an A/B performance comparison:

```text
Baseline : existing Maven/Surefire execution
POC      : same command with ScenarioMesh enabled
```

Compare test count, pass/fail/skip results, total regression duration, CPU/agent utilization and produced reports.

### Existing Jenkins pipelines

If Jenkins currently runs a normal Maven command such as:

```groovy
sh 'mvn clean test'
```

that command does not need to change after the target repository has been initialized and the ScenarioMesh artifacts are resolvable on the Jenkins agent.

For the current development snapshot, a Jenkins POC must first make `0.1.1-SNAPSHOT` available to the agent, for example by building/installing ScenarioMesh in that workspace or publishing the artifacts to the team's Maven repository. A future released version should be consumed from the configured artifact repository instead of rebuilding ScenarioMesh in every target job.

### What is automatic and what is not

Automatic during a normal Maven command:

- Surefire/Failsafe lifecycle inspection;
- framework/provider detection;
- adapter selection in `auto` mode;
- target runtime classpath resolution;
- Maven test-selection preservation;
- compatibility preflight;
- safe takeover or native pass-through;
- local worker startup;
- dynamic/history-aware scheduling;
- worker/result tracking;
- report generation.

You configure only operational policy you want to override, such as worker count, scheduling strategy, logging, report directory, explicit adapter intent, or distributed/remote worker settings.

The CLI can also explicitly delegate a run to the production Maven runtime:

```bash
java -jar scenariomesh-cli/target/scenariomesh-cli-0.1.1-SNAPSHOT.jar \
  run --root /path/to/my-automation-project
```

## Configuration

No `scenariomesh.yml` is required for supported zero-config repositories. When a file is present, `configVersion: 1` is required and unknown keys are rejected.

Precedence is centralized:

```text
Maven/system property
        ↓
environment variable
        ↓
scenariomesh.yml / scenariomesh.yaml
        ↓
documented default
```

Example:

```yaml
scenariomesh:
  configVersion: 1
  enabled: true

  execution:
    adapter: auto
    adapterMismatchPolicy: fail

  scheduling:
    strategy: history-lpt

  workers:
    count: 4

  reporting:
    directory: target/scenariomesh

  logging:
    liveConsole: true
    workerFiles: true
    showConfiguration: true
    showProgress: true
```

Disable takeover for a run with:

```bash
mvn test -Dscenariomesh.enabled=false
```

See [`docs/configuration.md`](docs/configuration.md) and [`scenariomesh.example.yml`](scenariomesh.example.yml).

## Compatibility / release baseline

- Java 17 is the minimum runtime.
- Java 17 and 21 are primary compatibility gates.
- Java 25 LTS is covered by the release smoke matrix.
- Maven 3.9.x is the production support line and the release matrix pins Maven 3.9.16.
- `./mvnw` pins Maven 3.9.16 and verifies the downloaded distribution checksum.
- Maven 3.10.0-rc-1 is a blocking preview gate; it is not a production support claim before GA qualification.
- Maven 4.0.0-rc-6 is a blocking preview gate on Java 17, 21, and 25 with representative takeover, hostile-classpath, Failsafe, framework, and native pass-through tests. Maven 4 remains a preview claim until its upstream GA line is pinned and qualified.
- Snapshot builds are not production releases.

A repository/runtime combination is promoted from native pass-through to ScenarioMesh takeover only after semantic equivalence is proven for selected logical tests, stable identities, pass/fail/skip outcomes, lifecycle behavior, build exit semantics, and required downstream reports.

See [`docs/release-strategy.md`](docs/release-strategy.md).

## Architecture boundaries

```text
Maven integration
→ determines whether and where ScenarioMesh may take ownership

Adapter layer
→ owns framework-native discovery/execution semantics

Core domain
→ framework-neutral task/result contracts

Scheduler
→ orders eligible work while preserving lifecycle affinity

Coordinator
→ owns run orchestration, leases, workers, and liveness

Worker runtime
→ executes selected work in isolated JVMs

Reporting
→ produces built-in reports and extension SPIs
```

Framework-specific logic stays out of the coordinator/scheduler. Selenium/browser-specific behavior stays in target projects or optional integrations.

More detail:

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/mvp.md`](docs/mvp.md)
- [`docs/configuration.md`](docs/configuration.md)
- [`docs/adapter-development.md`](docs/adapter-development.md)
- [`docs/security.md`](docs/security.md)
- [`docs/jenkins-distributed.md`](docs/jenkins-distributed.md)
- [`docs/scheduling.md`](docs/scheduling.md)
- [`docs/diagnostics.md`](docs/diagnostics.md)
- [`docs/reporting-integrations.md`](docs/reporting-integrations.md)
- [`docs/opentelemetry.md`](docs/opentelemetry.md)
- [`docs/release-strategy.md`](docs/release-strategy.md)
