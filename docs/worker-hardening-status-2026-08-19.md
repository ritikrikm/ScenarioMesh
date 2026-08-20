# ScenarioMesh Worker Hardening Status — 2026-08-19

Branch: `agent/worker-hardening-test`
Base branch: `agent/mvp-runtime`

This document tracks the worker hardening work point-by-point. Each point is implemented only when it can be added without prematurely forcing the larger future worker-lifecycle redesign.

## Baseline before hardening

The current MVP uses a fixed pool of persistent isolated worker JVMs. Each worker connects to the coordinator, receives multiple tasks, returns results, and is destroyed at the end of the run. The pool has startup and shutdown timeouts, but task execution previously had no result timeout.

## Point 1 — Hung worker task can block the run forever

### Previous behavior

```text
RUN task
  -> coordinator calls connection.read()
  -> worker/test hangs
  -> no RESULT
  -> read waits indefinitely
  -> worker executor never terminates
  -> Maven run can remain stuck indefinitely
```

### Fix implemented

Added one centralized configuration value:

```yaml
scenariomesh:
  workers:
    taskTimeout: PT15M
```

Canonical JVM property:

```text
-Dscenariomesh.workers.taskTimeout=PT15M
```

Backward-compatible singular alias:

```text
-Dscenariomesh.worker.taskTimeout=PT15M
```

Environment variable is derived through the existing centralized config naming logic:

```text
SCENARIOMESH_WORKERS_TASK_TIMEOUT
```

Default value: `PT15M`.

The duration is validated as positive through the immutable `ScenarioMeshConfig` constructor.

During a task, the worker socket now temporarily uses this timeout while waiting for a `RESULT`. The previous socket timeout is restored after the read.

If the timeout expires:

```text
RUN task
  -> taskTimeout exceeded
  -> WORKER_FAILURE result
  -> worker is retired
  -> remaining healthy workers continue
  -> coordinator is no longer blocked forever by that worker
```

A worker that produces a `WORKER_FAILURE` is now forcibly retired rather than being left alive after its coordinator execution loop has stopped.

### Tests added

Config regression coverage now checks:

- default `PT15M` value;
- YAML override;
- system-property precedence over environment/YAML;
- legacy singular property alias;
- rejection of a zero/non-positive timeout.

Existing CI provides the wider E2E regression gate on Java 17 and Java 21 and runs ScenarioMesh through normal Maven commands for JUnit 5, Cucumber JUnit Platform, Cucumber JUnit 4, TestNG, Failsafe, and pass-through projects.

### Verification status

- Source/config regression tests: added to the branch.
- Branch integrity: verified; the hardening branch is ahead of `agent/mvp-runtime` and contains only the intended Point 1 code/test/documentation changes.
- E2E CI: push-triggered by the repository workflow, but the currently available GitHub connector only exposes PR-triggered workflow runs for direct inspection. Therefore **Point 1 is not marked E2E-passed yet** from this session.
- Point 2 must not be treated as started until Point 1 E2E result is observable/confirmed.

### Remaining limitation after Point 1

A timed-out worker is retired but is **not yet replaced**. Pool capacity can therefore shrink during a run. Worker replacement is deliberately a separate future point because it needs an explicit mutable worker lifecycle and coordinator-owned replacement semantics.

## Current hardening backlog

| Area | Status | Size |
|---|---|---|
| Task execution timeout | Implemented; E2E confirmation pending | Small/medium |
| Retire worker after communication failure | Implemented with Point 1 | Small |
| Worker replacement | Future | Large |
| Infrastructure-only task retry/requeue | Future | Medium/large |
| Worker memory/task-count recycling | Future | Large |
| Heap/process-tree health monitoring | Future | Large |
| Per-task cleanup hooks | Future | Medium |
| Browser descendant-process cleanup | Future | Medium/large |
| Minimum-ready degraded startup mode | Future/optional | Medium |

## Design rules being followed

- Configuration remains centralized; no duplicated property/YAML/environment parsing.
- No hard-coded operational timeout inside coordinator logic.
- Normal test failure is kept separate from worker/infrastructure failure.
- No `System.gc()` based memory strategy.
- No automatic retry until retry safety and idempotency policy are explicit.
- No worker replacement shortcut that bypasses scheduler/lifecycle ownership.
- Existing normal `mvn test` behavior remains the primary E2E compatibility contract.
