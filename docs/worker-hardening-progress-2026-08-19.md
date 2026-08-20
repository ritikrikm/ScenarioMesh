# ScenarioMesh worker hardening progress — 2026-08-19

Branch: `agent/worker-hardening-test`

This file tracks the second hardening pass while CI verification is active.

## Implemented in this pass

- Infrastructure-only retry/requeue with explicit attempt propagation.
- Retry work returned through the scheduler rather than a coordinator-private queue.
- Configurable `execution.infrastructureRetries`; default `0` preserves existing behavior.
- Configurable task-count recycling with `workers.maxTasksPerWorker`; default `0` disables it.
- Worker heap telemetry after each task and configurable `workers.maxHeapUsagePercent`; default `0` disables recycling.
- ServiceLoader-based per-task cleanup extension point.
- Worker process-tree cleanup when a worker is retired or the pool shuts down.
- Configurable degraded startup with `workers.minimumReady`; default equals resolved `workers.count`.
- Protocol version 2 carries RUN attempt and RESULT telemetry.

## Verification being added

- Crash without retry remains a terminal `WORKER_FAILURE` while queued work continues on a replacement.
- Crash with one infrastructure retry re-runs the task on a fresh worker at attempt 2 and does not leak the recovered failed attempt into terminal results.
- Normal assertion failures do not trigger retry/replacement unless an independent recycling policy requests it.
- `maxTasksPerWorker=1` deterministically recycles a healthy single-worker fixture between tasks.
- Java 17 and Java 21 full E2E matrix remains the acceptance gate.

This is an in-progress verification note; final status is recorded in `worker-hardening-status-2026-08-19.md` after the fresh E2E matrix passes.
