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

During a task, the worker socket temporarily uses this timeout while waiting for a `RESULT`. The previous socket timeout is restored after the read.

If the timeout expires:

```text
RUN task
  -> taskTimeout exceeded
  -> WORKER_FAILURE result
  -> worker is retired
  -> remaining healthy workers continue
  -> coordinator is no longer blocked forever by that worker
```

A worker that produces a `WORKER_FAILURE` is forcibly retired rather than being left alive after its coordinator execution loop has stopped.

### Tests added

Config regression coverage checks:

- default `PT15M` value;
- YAML override;
- system-property precedence over environment/YAML;
- legacy singular property alias;
- rejection of a zero/non-positive timeout.

### E2E verification — PASSED

Point 1 was verified through draft PR #2 using the repository's existing full CI matrix.

Final verification run:

```text
GitHub Actions run: #252
Run id: 32326950255
Java 17: SUCCESS
Java 21: SUCCESS
```

Both Java matrix jobs passed all substantive checks:

- full `mvn -B clean install`;
- JUnit 5 normal Maven command with ScenarioMesh takeover;
- Cucumber JUnit Platform normal Maven command;
- Cucumber JUnit 4 normal Maven command;
- TestNG normal Maven command;
- Failsafe integration-test/verify lifecycle takeover;
- custom Surefire selection pass-through;
- unsupported generic JUnit 4 pass-through;
- ScenarioMesh report generation;
- modern report content and machine-readable timing checks;
- verification that pass-through projects were not incorrectly taken over.

The E2E logs showed workers launching, reaching READY, dynamically receiving tasks, returning terminal results, and Maven builds finishing successfully.

### CI issue discovered during verification

The first verification run exposed a stale CI-only assertion. The workflow expected the report text:

```text
Sequential-equivalent scenario work
```

but the current report implementation, including the unchanged `agent/mvp-runtime` base branch, renders:

```text
Estimated serial execution time
```

The stale grep was updated on the test branch to match the actual report contract. No worker/runtime implementation change was required for that CI failure.

### Point 1 status

**IMPLEMENTED + E2E CONFIRMED**

### Remaining limitation after Point 1

A timed-out worker is retired but is **not yet replaced**. Pool capacity can therefore shrink during a run. Worker replacement is deliberately a separate point because it needs explicit coordinator-owned replacement semantics rather than a shortcut inside the worker loop.

## Current hardening backlog

| Area | Status | Size |
|---|---|---|
| Task execution timeout | Implemented + E2E confirmed | Small/medium |
| Retire worker after communication failure | Implemented with Point 1 + E2E confirmed | Small |
| Worker replacement | Next design point | Large |
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
