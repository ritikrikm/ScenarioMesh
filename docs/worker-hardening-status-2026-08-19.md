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

Added `examples/worker-crash-recovery-example` with exactly one worker and ordered JUnit 5 tasks. The first task terminates the JVM, and two following tasks must execute on the replacement worker.

The CI check requires Maven to remain non-zero for the original infrastructure failure while proving `worker-2` was created and completed the queued tests.

### E2E verification

```text
GitHub Actions run: #262
Run id: 32327226564
Java 17: SUCCESS
Java 21: SUCCESS
```

### Point 2 status

**IMPLEMENTED + E2E CONFIRMED**

---

## Point 3 — Infrastructure-only retry/requeue

### Review result

**DEFERRED — requires a proper protocol/scheduler design.**

The current scheduling port has no requeue contract, the RUN protocol carries no attempt number, and the worker currently creates `ExecutionContext` with attempt `1`.

A shortcut requeue would therefore create incorrect execution metadata and unclear idempotency semantics. A future implementation should add explicit retry configuration, scheduler-owned requeue, protocol attempt propagation, and an infrastructure-only default policy. Normal assertion failures must remain separate.

---

## Point 4 — Adapter throws a normal Exception

### Review result

**CURRENT BEHAVIOR IS CORRECT — no production change required.**

`WorkerMain` catches adapter `Exception`, converts it to an infrastructure result, sends a terminal result, and keeps the worker JVM available for later tasks.

---

## Point 5 — Fatal JVM Error

### Review result

**DO NOT catch `Throwable`.**

Fatal errors such as `OutOfMemoryError`, `StackOverflowError`, or serious linkage problems can leave a worker JVM unsafe. The safer behavior is to allow the process/connection to fail and use Point 2's clean worker replacement path for later queued work.

---

## Point 6 — Normal test failure must not replace a worker

### Contract

A normal test/assertion failure is not a worker failure. The same healthy worker must remain reusable.

### Deterministic E2E fixture

Added `examples/test-failure-worker-reuse-example` with one worker and ordered JUnit 5 tasks:

```text
a_testFailureDoesNotPoisonWorker -> TEST_FAILURE
b_sameWorkerStillRuns            -> PASS on worker-1
c_sameWorkerStillRunsAgain       -> PASS on worker-1
```

CI asserts that Maven reports the expected test failure, later tasks still pass on `worker-1`, and no `REPLACED` message occurs.

### E2E verification

```text
GitHub Actions run: #271
Run id: 32327453584
Java 17: SUCCESS
Java 21: SUCCESS
```

The same run also revalidated Point 2's crash/replacement fixture and all existing framework/pass-through/report tests.

### Point 6 status

**E2E CONTRACT CONFIRMED — no production behavior change required.**

---

## Double-check hardening review — 2026-08-19

After Points 1, 2 and 6 were green, the branch was reviewed again for concurrency, timeout conversion, accidental retry behavior, cleanup races, and duplicated worker-launch logic.

Two small edge cases were found and fixed:

1. **Socket timeout range validation.** Java socket timeouts are integer milliseconds. Worker startup, task and shutdown durations are now validated centrally so a value larger than `Integer.MAX_VALUE` milliseconds fails early as invalid configuration instead of overflowing later inside worker execution. Discovery timeout remains only positive because it does not use `Socket#setSoTimeout`.
2. **Concurrent lifecycle maps.** Worker replacement can mutate process/output-pump tracking while other worker loops are active. These maps now use `ConcurrentHashMap`; active connections already use `CopyOnWriteArrayList`.

Regression coverage was added for oversized startup/task/shutdown socket timeouts.

### Final double-check E2E verification

```text
GitHub Actions run: #283
Run id: 32327847697
Java 17: SUCCESS
Java 21: SUCCESS
```

Both JDKs passed the full reactor build, JUnit 5, both Cucumber modes, TestNG, Failsafe, deliberate worker crash/replacement, normal test-failure worker reuse, Surefire pass-through, unsupported JUnit 4 pass-through, and all report assertions.

### Double-check status

**COMPLETE + E2E CONFIRMED**

---

## Current hardening backlog

| Area | Status | Size |
|---|---|---|
| Point 1 — task execution timeout | Implemented + E2E confirmed | Small/medium |
| Point 2 — worker replacement | Implemented + E2E confirmed | Medium/large |
| Point 3 — infrastructure-only retry/requeue | Deferred: protocol/policy work | Medium/large |
| Point 4 — normal adapter Exception | Existing behavior correct | None |
| Point 5 — fatal JVM Error | Covered by replacement strategy | None now |
| Point 6 — test failure keeps worker alive | E2E confirmed | Test-only |
| Socket-timeout range hardening | Implemented + E2E confirmed | Small |
| Concurrent lifecycle maps | Implemented + E2E confirmed | Small |
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
