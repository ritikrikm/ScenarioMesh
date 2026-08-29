# ScenarioMesh Worker Hardening Status — 2026-08-19

Branch: `agent/worker-hardening-test`
Base branch: `agent/mvp-runtime`

This is the authoritative status for the worker-hardening pass. A behavior is marked E2E confirmed only when it has passed the Java 17 and Java 21 CI matrix.

## Final verification

```text
GitHub Actions run: #335
Run id: 32329038344
Java 17: SUCCESS
Java 21: SUCCESS
```

The final matrix passed the full reactor build plus JUnit 5, Cucumber JUnit Platform, Cucumber JUnit 4, TestNG, Failsafe, custom Surefire pass-through, unsupported JUnit 4 pass-through, reporting checks, crash replacement, infrastructure retry, normal-failure worker reuse, task-count recycling, heap recycling, per-task cleanup hooks, and descendant-process cleanup.

---

## Point 1 — Hung worker task

**Status: IMPLEMENTED + E2E CONFIRMED**

`workers.taskTimeout` prevents a task from blocking the run forever. Socket-backed worker timeouts are validated centrally, including rejecting values larger than Java's integer-millisecond socket timeout range.

On timeout the task becomes `WORKER_FAILURE`; the failed worker is retired and capacity can be restored by the worker replacement path.

Default task timeout remains `PT15M`.

---

## Point 2 — Dead worker replacement

**Status: IMPLEMENTED + E2E CONFIRMED**

Worker replacement is coordinator-owned and uses the same shared launch path as initial workers. Replacement workers inherit the same runtime classpath, JVM args, system properties, token, working directory and logging behavior.

Important behavior:

```text
worker dies
 -> current attempt becomes WORKER_FAILURE
 -> failed connection/process retired
 -> replacement worker starts
 -> queued work continues
```

Replacement does not silently retry the failed task unless the explicit infrastructure retry policy is enabled.

Lifecycle maps are concurrent and replacement handshakes are serialized so simultaneous failures cannot consume the wrong HELLO connection.

---

## Point 3 — Infrastructure-only retry/requeue

**Status: IMPLEMENTED + E2E CONFIRMED**

The earlier shortcut was deliberately deferred until the protocol and scheduler could represent retries correctly. That design work is now complete.

Implemented:

- `execution.infrastructureRetries` configuration, default `0`;
- scheduler-owned `requeue(ScenarioTask)` contract;
- protocol version 2 with explicit RUN attempt metadata;
- worker `ExecutionContext` receives the real attempt number;
- synthetic worker failures also preserve the attempt number;
- only `WORKER_FAILURE` and `INFRASTRUCTURE_FAILURE` are retryable;
- normal `TEST_FAILURE` is never retried by this policy;
- retry runs on a fresh worker JVM;
- recovered failed attempts do not leak into terminal result totals/reports;
- retry count is bounded, so there is no uncontrolled retry loop.

The deterministic crash fixture proves attempt 1 can hard-stop the worker, worker-2 is created, and the same task later passes as attempt 2 when one infrastructure retry is enabled.

---

## Point 4 — Adapter throws a normal Exception

**Status: EXISTING BEHAVIOR PRESERVED + E2E COMPATIBILITY CONFIRMED**

A normal adapter `Exception` becomes `INFRASTRUCTURE_FAILURE`; the worker can remain usable when retries are disabled. If infrastructure retries are enabled, that failed task is requeued and retried on a fresh worker.

This remains distinct from assertion/test failures.

---

## Point 5 — Fatal JVM Error / hard worker death

**Status: REPLACEMENT STRATEGY CONFIRMED**

ScenarioMesh intentionally does not catch `Throwable`. Fatal conditions such as severe JVM errors can leave process state unsafe, so worker death is treated as loss of the worker and later queued work moves to a clean replacement JVM.

The hard-crash E2E fixture uses `Runtime.halt(...)`, proving replacement does not depend on graceful Java shutdown.

---

## Point 6 — Normal test failure must keep worker alive

**Status: E2E CONFIRMED**

A normal assertion/test failure remains `TEST_FAILURE`. With recycling disabled, it does not poison or replace the worker. The single-worker fixture proves later tests execute successfully on the same `worker-1` and no replacement is logged.

---

## Worker recycling

### Task-count recycling

**Status: IMPLEMENTED + E2E CONFIRMED**

`workers.maxTasksPerWorker` controls planned recycling after a worker has completed a configured number of terminal tasks.

- `0` = disabled (default);
- positive value = retire/recreate the worker after that many tasks when queued work remains.

The E2E fixture with `maxTasksPerWorker=1` proves healthy workers are replaced between tasks without changing test result semantics.

### Heap-based recycling

**Status: IMPLEMENTED + E2E CONFIRMED**

Protocol v2 returns post-task worker heap telemetry (`usedHeapBytes` / `maxHeapBytes`). `workers.maxHeapUsagePercent` can recycle a worker when the post-task heap percentage reaches the configured threshold.

- `0` = disabled (default);
- `1..100` = recycle threshold.

This is deliberate post-task health management; ScenarioMesh does not call `System.gc()` and does not continuously interfere with the target test while it is running.

---

## Per-task cleanup hooks

**Status: IMPLEMENTED + E2E CONFIRMED**

A `WorkerTaskCleanup` extension point is available through Java `ServiceLoader` and executes after each task inside the worker JVM.

The cleanup hook receives:

- `ScenarioTask`;
- `ExecutionContext` including worker and attempt;
- the task `ExecutionResult`.

A cleanup-hook exception becomes `INFRASTRUCTURE_FAILURE` rather than being silently ignored.

The E2E fixture registers a real ServiceLoader provider and proves it executes once after each of two tasks.

---

## Descendant process cleanup

**Status: IMPLEMENTED FOR DISCOVERABLE DESCENDANTS + E2E CONFIRMED**

When ScenarioMesh retires or shuts down a live worker, it uses Java `ProcessHandle` to destroy the worker's descendant process tree before/with the worker process. This is intended to clean resources such as browser/driver child processes that are still descendants of that worker.

The E2E fixture starts a long-running child Java process from worker-1, forces worker recycling, then proves from worker-2 that the original child PID is no longer alive.

### Remaining hard-kill orphan limitation

A process that becomes orphaned/re-parented by the operating system after an abrupt worker `halt`, SIGKILL, container kill, or equivalent may no longer appear in `ProcessHandle.descendants()` for the dead worker. Portable Java cannot reliably rediscover every such already-orphaned external process after parentage is lost.

Therefore **crash-orphan containment across arbitrary operating systems is not claimed as solved**. A future stronger design would require OS/container-specific process groups, job objects, cgroups, or equivalent ownership tracking. The current implementation remains portable and safely cleans descendants it can prove belong to the worker.

---

## Minimum-ready degraded startup

**Status: IMPLEMENTED; CONFIG/BUILD VERIFIED**

`workers.minimumReady` allows ScenarioMesh to continue when fewer than the requested workers become ready before startup timeout, provided the configured minimum has connected.

Default behavior is intentionally unchanged:

```text
minimumReady = resolved workers.count
```

So existing repositories still require every configured worker unless they explicitly opt into degraded startup.

If ready workers are below the minimum, startup fails. If the minimum is met, unconnected processes are retired and execution continues at degraded capacity.

A deterministic partial-startup E2E failure injector was intentionally not added to production solely for testing; range/default behavior is covered by configuration tests and the normal full worker-startup path remains covered by every framework E2E run.

---

## Configuration added by this hardening pass

```yaml
scenariomesh:
  configVersion: 1
  execution:
    infrastructureRetries: 0
  workers:
    count: 4
    minimumReady: 4
    maxTasksPerWorker: 0
    maxHeapUsagePercent: 0
    startupTimeout: PT30S
    taskTimeout: PT15M
    shutdownTimeout: PT10S
```

Defaults preserve existing behavior: no retry, no planned recycling, and all requested workers required at startup.

Configuration continues to use the existing centralized precedence/resolution pipeline rather than feature-specific parsers.

---

## Final backlog status

| Area | Final status |
|---|---|
| Task execution timeout | Implemented + E2E confirmed |
| Dead-worker replacement | Implemented + E2E confirmed |
| Infrastructure-only retry/requeue | Implemented + E2E confirmed |
| Normal adapter Exception handling | Correct behavior preserved |
| Fatal JVM Error strategy | Hard-crash replacement confirmed |
| Normal test failure keeps worker | E2E confirmed |
| Socket-timeout range validation | Implemented + E2E confirmed |
| Concurrent lifecycle tracking | Implemented + E2E confirmed |
| Task-count worker recycling | Implemented + E2E confirmed |
| Heap telemetry/recycling | Implemented + E2E confirmed |
| Per-task cleanup hooks | Implemented + E2E confirmed |
| Discoverable descendant-process cleanup | Implemented + E2E confirmed |
| Minimum-ready degraded startup | Implemented; config/build verified |
| Already-orphaned child containment after hard OS/JVM kill | Future OS/container-specific hardening |

## Engineering rules preserved

- DRY: initial/replacement/recycling launch through the same worker-launch path.
- Retry ownership remains in scheduler/coordinator policy, not framework adapters.
- Configuration parsing remains centralized.
- Normal test failure remains separate from infrastructure failure.
- Retries are explicit, bounded and disabled by default.
- Worker health recycling is explicit and disabled by default.
- No `System.gc()` strategy.
- Fatal JVM state is not hidden by catching `Throwable`.
- Existing Maven pass-through behavior is retained for unsupported execution semantics.
- Java 17 and Java 21 E2E compatibility remain the release gate.
