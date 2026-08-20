# ScenarioMesh worker hardening progress — 2026-08-19

Branch: `agent/worker-hardening-test`

This second hardening pass is complete for the portable, production-safe items selected in this branch.

Final E2E gate:

```text
GitHub Actions run: #335
Run id: 32329038344
Java 17: SUCCESS
Java 21: SUCCESS
```

Completed in this pass:

- infrastructure-only retry/requeue with explicit attempt propagation;
- scheduler-owned requeue;
- bounded `execution.infrastructureRetries`, disabled by default;
- task-count recycling, disabled by default;
- post-task heap telemetry and threshold recycling, disabled by default;
- ServiceLoader per-task cleanup hooks with failure propagation;
- discoverable descendant-process cleanup on worker retirement/shutdown;
- configurable minimum-ready degraded startup with unchanged all-workers-ready default;
- protocol v2 RUN attempt and RESULT telemetry;
- deterministic E2E fixtures for retry, task recycling, heap recycling, cleanup hooks and process-tree cleanup.

The authoritative design, configuration, caveats and final backlog are documented in `worker-hardening-status-2026-08-19.md`.

One limitation remains intentionally documented rather than hidden: after a hard OS/JVM kill, an external child process that has already been orphaned/re-parented may no longer be discoverable through portable Java `ProcessHandle.descendants()`. Strong containment for that case requires an OS/container-specific future mechanism such as process groups, job objects or cgroups.
