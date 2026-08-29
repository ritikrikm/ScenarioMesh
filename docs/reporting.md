# ScenarioMesh reporting and run completion

ScenarioMesh writes a framework-neutral run report only after every discovered task has reached a terminal result or has been converted into an explicit infrastructure result. Report generation must not depend on a user stopping Maven manually.

## Run completion and worker shutdown

At the end of a run the coordinator stops assigning work, sends each worker a protocol `STOP`, waits for an `ACK` for at most the configured `workers.shutdownTimeout`, closes the worker control sockets, and terminates any child JVM that remains alive because the target test framework left non-daemon helper threads running.

The shutdown timeout is a bound for the pool cleanup path, not a timeout multiplied independently by every worker. This keeps local and CI completion deterministic. Target frameworks still own their own WebDriver/application teardown; ScenarioMesh only guarantees that its child worker processes cannot hold the Maven build open indefinitely.

## HTML report

`report.html` is self-contained. It has no CDN or network dependency, so it can be opened directly from a local filesystem or archived as a Jenkins/GitHub Actions artifact.

The report currently includes:

- total, passed, failed, and infrastructure-result counts;
- status filters for All / Passed / Failed / Infrastructure;
- free-text scenario search;
- worker filter;
- scenario status, display name, execution id, assigned worker, duration, and failure text;
- per-worker scenario count, pass/fail count, and accumulated scenario execution duration;
- ScenarioMesh wall-clock duration;
- sequential-equivalent scenario work;
- estimated time saved;
- observed speedup ratio.

The HTML is intentionally driven from the framework-neutral `ExecutionResult` model. Future filters or charts can therefore be added without teaching reporting about Cucumber, JUnit, TestNG, Selenium, or company-specific runner conventions.

## Timing definitions

### Wall-clock duration

The observed ScenarioMesh run duration measured by the coordinator. It includes ScenarioMesh discovery/worker orchestration overhead represented by the current `RunOutcome` duration.

### Worker execution time

For each worker, ScenarioMesh sums the actual durations of the scenarios executed by that worker. It excludes idle time and process startup time. This is why worker execution totals do not necessarily equal wall-clock duration.

### Sequential-equivalent scenario work

ScenarioMesh sums the actual durations reported by every scenario in the parallel run:

```text
sequentialEquivalent = Σ scenarioDuration
```

This is a useful estimate of the amount of scenario work that would have been performed serially.

It is **not** claimed to be a measured non-ScenarioMesh baseline. A real baseline would require executing the entire suite again sequentially, which can double runtime, consume additional environments/users, and alter stateful test data. ScenarioMesh therefore does not silently rerun a suite just to manufacture a performance number.

### Estimated time saved

```text
estimatedTimeSaved = sequentialEquivalent - ScenarioMeshWallClock
```

A negative value is possible when a very small run has more process/discovery overhead than parallel benefit. The report shows that honestly rather than clamping it to zero.

### Observed speedup

```text
observedSpeedup = sequentialEquivalent / ScenarioMeshWallClock
```

These values are also emitted into `summary.json` so CI/reporting integrations can consume them without scraping HTML.

## Future measured baseline mode

A future benchmark-only mode may deliberately execute an explicit sequential control run and compare it with a ScenarioMesh run. Such a mode must be opt-in because repeating stateful integration scenarios can be unsafe and expensive. It is not part of the normal product execution path.
