# ScenarioMesh configuration

ScenarioMesh is designed to work with **zero configuration** for repositories whose Maven/test execution model can be detected safely. The configuration file exists for three reasons:

1. to override operational defaults such as worker count, timeouts and observability;
2. to let a team state an expected execution adapter when a repository contains multiple test-framework libraries;
3. to make ambiguous or enterprise-specific intent explicit without hard-coding repository names, paths, framework versions, or company conventions into ScenarioMesh.

The default file is `scenariomesh.yml` in the Maven project root. `scenariomesh.yaml` is also accepted. If both exist, ScenarioMesh fails configuration validation instead of guessing. A custom file can be selected with `-Dscenariomesh.config.file=/path/to/file.yml` or `SCENARIOMESH_CONFIG_FILE`.

## Precedence

The same precedence is used everywhere ScenarioMesh resolves configuration:

1. Maven/user system property (`-Dscenariomesh...`)
2. environment variable (`SCENARIOMESH_...`)
3. `scenariomesh.yml` / `scenariomesh.yaml`
4. ScenarioMesh documented defaults

Command-line properties are therefore suitable for one-off CI/local overrides without editing the repository file.

## Complete product configuration

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

Every key is optional except that, when a file is present, `configVersion` must be supported. Missing values use ScenarioMesh defaults.

## Switch reference

### `configVersion`

**Why it exists:** config files are long-lived while ScenarioMesh evolves. Versioning prevents an older runtime from silently interpreting a newer file incorrectly.

**Options:** currently `1` only.

### `enabled`

**Why it exists:** ScenarioMesh must be easy to disable without modifying test code.

**Options:** `true` or `false`.

**Default:** `true`.

Equivalent override: `-Dscenariomesh.enabled=false`.

### `execution.adapter`

**Why it exists:** automatic discovery is the normal path, but enterprise repositories can contain Cucumber, JUnit and TestNG libraries at the same time even when only one owns the Maven test execution. This setting lets the repository owner state intent without changing ScenarioMesh code.

**Options in the current runtime:**

- `auto` — default. ScenarioMesh probes every available adapter and selects it automatically when exactly one adapter discovers executable tests. If more than one adapter claims executable tests, ScenarioMesh does not guess.
- `junit-platform` — explicitly use the JUnit Platform adapter. Native JUnit 5 and Cucumber running through the JUnit Platform engine are both discovered through the official launcher/test plan.
- `cucumber-junit4` — explicitly use the legacy Cucumber/JUnit 4 adapter.
- `testng` — explicitly use the native TestNG adapter.

Adapter IDs are intentionally strings rather than a config enum so future adapters can be added without redesigning the configuration model. An adapter ID that is not registered by the installed ScenarioMesh runtime fails with an actionable message.

Equivalent override: `-Dscenariomesh.execution.adapter=cucumber-junit4`.

### `execution.adapterMismatchPolicy`

**Why it exists:** an explicit adapter is a user assertion, not permission to run the wrong test framework. ScenarioMesh still collects evidence and validates the assertion.

**Options:**

- `fail` — default and recommended for CI. If the configured adapter is unavailable or discovers zero executable tests while another adapter does discover tests, fail before scenario execution and print the evidence.
- `use-detected` — if the configured adapter does not apply and **exactly one** other adapter discovers executable tests, use that uniquely detected adapter and emit a warning. If detection is still ambiguous, fail rather than guess.

This switch never makes ambiguous multi-adapter ownership automatic.

Equivalent override: `-Dscenariomesh.execution.adapterMismatchPolicy=use-detected`.

### `workers.count`

**Why it exists:** worker count is operational and repository/environment dependent.

**Options:** any integer greater than zero.

**Default:** `4`.

Equivalent override: `-Dscenariomesh.workers.count=8`.

For backward compatibility, the earlier compatibility property `-Dscenariomesh.workers=8` remains an alias.

### `workers.startupTimeout`

**Why it exists:** slow CI agents and large enterprise classpaths can take longer to start isolated JVMs.

**Options:** positive ISO-8601 duration such as `PT30S`, `PT1M`.

**Default:** `PT30S`.

### `workers.shutdownTimeout`

**Why it exists:** workers are given a bounded graceful-shutdown period before forced termination.

**Options:** positive ISO-8601 duration.

**Default:** `PT10S`.

### `workers.jvmArgs`

**Why it exists:** target repositories or CI environments may require worker JVM options. ScenarioMesh must not hard-code heap sizes, encodings, trust stores or other JVM policy.

**Options:** YAML list of JVM arguments.

Example:

```yaml
workers:
  jvmArgs:
    - -Xmx2g
    - -Dfile.encoding=UTF-8
```

**Default:** empty list.

Maven Surefire/Failsafe JVM arguments that ScenarioMesh has explicitly declared compatible are merged with these worker arguments; project-local operational overrides do not replace executor semantics.

### `discovery.timeout`

**Why it exists:** framework-native discovery can load a large test runtime. A bounded timeout prevents Maven from hanging forever when discovery is broken.

**Options:** positive ISO-8601 duration.

**Default:** `PT2M`.

### `reporting.directory`

**Why it exists:** local repositories and CI systems may require reports in different locations.

**Options:** absolute or project-relative path.

**Default:** `${project.build.directory}/scenariomesh` (normally `target/scenariomesh`).

### `logging.liveConsole`

**Why it exists:** Surefire/Failsafe normally make target-framework output visible in the Maven/Jenkins console. ScenarioMesh workers are separate JVMs, so ScenarioMesh must deliberately mirror their stdout/stderr back to the build console.

**Options:** `true` or `false`.

**Default:** `true`.

When enabled, each line is prefixed with its worker id, for example:

```text
[ScenarioMesh][worker-2] INFO com.example.steps.LoginSteps - login successful
```

The worker id prefix is added by ScenarioMesh. The remainder of the line is produced by the target project and keeps its existing logging style.

Equivalent override: `-Dscenariomesh.logging.liveConsole=false`.

### `logging.workerFiles`

**Why it exists:** interleaved parallel console output is useful live, but a per-worker file is much easier to inspect after a failure.

**Options:** `true` or `false`.

**Default:** `true`.

When enabled, raw worker stdout/stderr is persisted under the run directory:

```text
target/scenariomesh/runs/<run-id>/logs/worker-1.log
```

The worker stream is always drained even when this option and `liveConsole` are both false, preventing a verbose child JVM from blocking on a full process-output buffer.

Equivalent override: `-Dscenariomesh.logging.workerFiles=false`.

### `logging.showConfiguration`

**Why it exists:** users should be able to tell immediately what ScenarioMesh resolved instead of guessing which adapter, executor, scheduler or worker settings are active.

**Options:** `true` or `false`.

**Default:** `true`.

The startup summary includes requested Maven goals, executor takeover, lifecycle ownership, adapter intent, worker count, scheduler, logging switches, report directory and config source. Secrets and target-project system-property values are intentionally not printed.

Equivalent override: `-Dscenariomesh.logging.showConfiguration=false`.

### `logging.showProgress`

**Why it exists:** a parallel run needs visible operational state: worker creation, READY state, assignment, completed/failed counts, active workers and remaining queue.

**Options:** `true` or `false`.

**Default:** `true`.

Typical output:

```text
[ScenarioMesh] worker-1 READY
[ScenarioMesh] worker-2 RUN Create opportunity | completed=3/32 busy=4 queued=25
[ScenarioMesh] worker-3 PASSED Update lead | completed=4/32 failed=0 busy=3 queued=25
```

Equivalent override: `-Dscenariomesh.logging.showProgress=false`.

## Logging combinations

`liveConsole` and `workerFiles` are independent:

| liveConsole | workerFiles | Behavior |
|---|---|---|
| `true` | `true` | Live Maven/Jenkins output + per-worker files (default) |
| `true` | `false` | Live console only |
| `false` | `true` | Quiet target logs in console; per-worker files retained |
| `false` | `false` | Target output is consumed but not displayed/persisted; ScenarioMesh operational output can still be controlled separately |

`showConfiguration` and `showProgress` control ScenarioMesh's own operational information, not the target framework's logs.

## Auto-detection and explicit configuration

Configuration does not replace detection. ScenarioMesh always validates what it can observe.

With the default:

```yaml
execution:
  adapter: auto
```

ScenarioMesh probes candidate adapters using the target project's runtime classpath and compiled test roots. An adapter that is merely present as a dependency but discovers no executable tests is not selected.

If exactly one adapter owns executable tests, ScenarioMesh proceeds automatically. If multiple adapters discover executable tests, automatic mode fails clearly because dependency presence alone is insufficient evidence of Maven execution ownership.

With an explicit adapter:

```yaml
execution:
  adapter: cucumber-junit4
```

ScenarioMesh still probes the repository. If Cucumber/JUnit 4 is available and discovers executable tests, the assertion is accepted. If it does not, ScenarioMesh reports what each adapter found and applies `adapterMismatchPolicy`.

This design intentionally separates **user intent** from **runtime evidence**. It prevents stale configuration from silently changing which tests execute.

## Unknown keys

Unknown configuration keys are rejected. This is deliberate: a typo such as `workers.cout: 8` must not silently run with four workers. It also makes version compatibility fail-safe when newer ScenarioMesh versions introduce execution-affecting settings.

## Why there are no Selenium/browser/company-library switches here

ScenarioMesh operates above the test-framework layer. Selenium, REST Assured, Jackson, internal company libraries and other project dependencies are supplied through Maven's resolved test runtime classpath. They should not require ScenarioMesh-specific configuration unless they affect Maven/test execution semantics.
