# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for existing Java test automation repositories. Its primary product rule is simple:

> **Prove compatibility, then take ownership. If compatibility cannot be proven, leave native Maven execution alone.**

For supported Maven repositories, teams keep using normal commands such as `mvn test` and `mvn verify`. ScenarioMesh participates inside Maven, discovers framework-native executable work, starts isolated worker JVMs, dynamically schedules compatible work, preserves Maven success/failure semantics, and writes standard reports.

ScenarioMesh is not a Selenium framework, WebDriver proxy, Gherkin parser, replacement for JUnit/Cucumber/TestNG, or shell wrapper around Maven.

## Current support

| Repository style | Status |
|---|---|
| Maven + JUnit 5 / JUnit Platform | Supported |
| Maven + Cucumber JUnit Platform engine | Supported through the JUnit Platform adapter |
| Maven + Cucumber JUnit 4 runner | Supported |
| Generated Cucumber JUnit 4 runners exposing executable leaves | Supported |
| Compatible Maven Surefire `test` execution | Supported |
| Compatible Maven Failsafe `integration-test` / `verify` execution | Supported; unsupported semantics pass through |
| Standard method-level TestNG `@Test` | Supported |
| Generic JUnit 4 without Cucumber | Native Maven pass-through |
| TestNG `suiteXmlFiles` | Supported as atomic TestNG lifecycle scopes; individual outcomes are materialized after native TestNG execution |
| Factory-heavy TestNG discovered without a suite XML | Pass-through when ScenarioMesh cannot prove equivalent isolated semantics |
| Gradle | Not supported yet |

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

## Installation

Build/install ScenarioMesh during development:

```bash
mvn clean install
```

A target Maven repository activates the core extension in `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>io.scenariomesh</groupId>
    <artifactId>scenariomesh-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Then continue using normal project commands:

```bash
mvn test
mvn verify
mvn clean install
```

The CLI can initialize the required files idempotently:

```bash
java -jar scenariomesh-cli-0.1.0-SNAPSHOT.jar init --project /path/to/project
```

It can also run compatibility diagnostics without taking ownership:

```bash
java -jar scenariomesh-cli-0.1.0-SNAPSHOT.jar doctor --deep --root /path/to/project
```

or explicitly delegate a run to the production Maven runtime:

```bash
java -jar scenariomesh-cli-0.1.0-SNAPSHOT.jar run --root /path/to/project
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
