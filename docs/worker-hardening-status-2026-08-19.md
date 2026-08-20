# ScenarioMesh Worker Hardening Status — 2026-08-19

Branch: `agent/worker-hardening-test`
Base branch: `agent/mvp-runtime`

This document tracks worker hardening point-by-point. A point is marked complete only after implementation and E2E verification.

## Baseline before hardening

The MVP uses persistent isolated worker JVMs. Workers connect to the coordinator, execute multiple dynamically assigned tasks, return results, and are destroyed at the end of the run.

---

## Point 1 — Hung worker task can block the run forever

### Previous behavior

```text
RUN task
  -> coordinator waits on connection.read()
  -> worker/test hangs
  -> no RESULT
  -> read waits indefinitely
  -> Maven run can remain stuck indefinitely
```

### Fix implemented

Added centralized `workers.taskTimeout` configuration with a default of `PT15M`.

Supported forms:

```yaml
scenariomesh:
  workers:
    taskTimeout: PT15M
```

```text
-Dscenariomesh.workers.taskTimeout=PT15M
-Dscenariomesh.worker.taskTimeout=PT15M
SCENARIOMESH_WORKERS_TASK_TIMEOUT=PT15M
```

The value is resolved through the existing configuration pipeline and validated as a positive duration.

During a task, the worker socket temporarily uses this timeout while waiting for `RESULT`. The previous socket timeout is restored afterward.

On timeout:

```text
RUN
 -> timeout
 -> WORKER_FAILURE
 -> retire failed worker
 -> coordinator loop no longer hangs forever
```

### Tests

Config regression coverage checks:

- default timeout;
- YAML override;
- property/environment precedence;
- legacy singular alias;
- rejection of non-positive timeout.

### E2E verification

```text
GitHub Actions run: #252
Run id: 32326950255
Java 17: SUCCESS
Java 21: SUCCESS
```

Full Maven build, JUnit 5, both Cucumber modes, TestNG, Failsafe, reports and pass-through behavior all passed.

During verification a stale CI-only report grep was found. CI expected `Sequential-equivalent scenario work`, while both the base and hardening branch report implementation render `Estimated serial execution time`. The test assertion was corrected on the hardening branch; no worker runtime change was needed for that issue.

### Point 1 status

**IMPLEMENTED + E2E CONFIRMED**

---

## Point 2 — Dead worker is never replaced

### Previous behavior

A worker communication failure produced `WORKER_FAILURE`, retired that worker loop, and reduced pool capacity permanently for the rest of the run.

```text
4 workers
 -> worker-2 dies
 -> task becomes WORKER_FAILURE
 -> worker-2 loop exits
 -> only 3 workers remain
```

If every worker failed while tasks remained queued, those tasks could not execute and were eventually surfaced as missing infrastructure results.

### Design plan

Replacement was deliberately implemented as coordinator-owned lifecycle behavior rather than launching a process directly from arbitrary failure handling.

The key design choice is that each original coordinator execution-loop slot remains alive. If its worker dies and queued work still exists, that same loop slot:

```text
failed connection
 -> retire failed worker
 -> launch replacement through shared worker-launch code
 -> validate replacement HELLO
 -> attach replacement connection
 -> continue pulling queued tasks
```

This avoids reopening the already-shutdown executor or creating a second scheduling mechanism.

### Fix implemented

- Refactored worker launch into one reusable `launchWorker(...)` path used by both initial workers and replacements.
- Added monotonic unique worker IDs (`worker-1`, `worker-2`, ...), preserving process/log traceability across replacements.
- Changed active connection tracking to `CopyOnWriteArrayList` so replacing a connection is safe while worker loops are executing.
- Added serialized replacement handshake through a dedicated replacement lock so concurrent failures cannot accidentally consume each other's replacement HELLO sockets.
- Replacement workers use the exact same runtime classpath, effective JVM arguments, effective system properties, token, logging and project directory as initial workers.
- Failed connections are removed and closed before replacement.
- Failed processes are forcibly retired.
- A replacement is only created when scheduler work remains queued.
- If replacement startup itself fails, the failure is logged and other healthy workers continue; ScenarioMesh does not recursively create an uncontrolled replacement loop.

### Important semantics intentionally preserved

Point 2 does **not** retry the task that was executing when the worker died.

```text
Task A on worker-1
 -> worker-1 crashes
 -> Task A = WORKER_FAILURE
 -> worker-2 starts
 -> worker-2 processes Task B, Task C, ...
```

This is intentional. Worker replacement restores capacity; retry/requeue is a separate correctness policy.

### Deterministic E2E fixture

Added:

```text
examples/worker-crash-recovery-example
```

The fixture configures exactly one worker and contains ordered JUnit 5 tasks:

```text
a_crashWorker
 -> Runtime.getRuntime().halt(23)

b_runsAfterReplacement
c_alsoRunsAfterReplacement
```

The CI check requires all of the following:

- the Maven invocation returns non-zero because the crashed task is still an infrastructure failure;
- ScenarioMesh creates its report and summary;
- log contains `worker-2 REPLACED worker-1`;
- worker-2 passes both queued follow-up tests;
- summary contains `WORKER_FAILURE`;
- summary contains worker-2.

This proves replacement without hiding the original failure or introducing retry semantics.

### E2E verification

```text
GitHub Actions run: #262
Run id: 32327226564
Java 17: SUCCESS
Java 21: SUCCESS
```

Both JDKs passed:

- full `mvn -B clean install`;
- JUnit 5 normal Maven takeover;
- Cucumber JUnit Platform;
- Cucumber JUnit 4;
- TestNG;
- Failsafe lifecycle takeover;
- deliberate worker-crash replacement test;
- custom Surefire pass-through;
- unsupported JUnit 4 pass-through;
- report existence/content validation.

### Point 2 status

**IMPLEMENTED + E2E CONFIRMED**

---

## Current hardening backlog

| Area | Status | Size |
|---|---|---|
| Point 1 — task execution timeout | Implemented + E2E confirmed | Small/medium |
| Point 2 — worker replacement | Implemented + E2E confirmed | Medium/large |
| Point 3 — infrastructure-only task retry/requeue | Future policy decision | Medium/large |
| Worker memory/task-count recycling | Future | Large |
| Heap/process-tree health monitoring | Future | Large |
| Per-task cleanup hooks | Future | Medium |
| Browser descendant-process cleanup | Future | Medium/large |
| Minimum-ready degraded startup mode | Future/optional | Medium |

## Design rules being followed

- DRY: initial and replacement workers share one launch path.
- Configuration remains centralized; no duplicated property/YAML/environment parsing.
- Worker replacement is coordinator-owned.
- Normal test failure remains separate from worker/infrastructure failure.
- No hidden automatic retry.
- No `System.gc()` memory strategy.
- Replacement workers inherit the same runtime configuration as initial workers.
- Existing normal `mvn test` behavior remains the main E2E compatibility contract.
