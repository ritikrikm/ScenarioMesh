# Scheduling

ScenarioMesh uses dynamic work assignment: a worker receives another eligible work unit when it becomes available rather than receiving a static partition up front. Lifecycle-scope affinity and remote adapter/engine capability checks apply regardless of the selected ordering strategy.

## Strategies

The default is history-aware longest-processing-time-first scheduling:

```yaml
scenariomesh:
  configVersion: 1
  scheduling:
    strategy: history-lpt
```

`history-lpt` uses ScenarioMesh's persisted execution history to attach duration estimates and starts longer estimated work first. Tasks without history retain deterministic discovery/FIFO order relative to one another. Corrupt or unsupported history is quarantined and the run falls back to deterministic cold-start behavior; history is an optimization, not an ownership requirement.

Strict FIFO is available when predictable discovery order is preferred:

```yaml
scenariomesh:
  configVersion: 1
  scheduling:
    strategy: fifo
```

With `fifo`, duration estimates are deliberately removed before scheduling so stale history cannot influence ordering. ScenarioMesh still records successful execution durations so a later switch back to `history-lpt` has useful data.

Equivalent overrides:

```text
-Dscenariomesh.scheduling.strategy=fifo
SCENARIOMESH_SCHEDULING_STRATEGY=fifo
```

The normal precedence applies: Maven/system property, then environment, then YAML, then default.

## Correctness boundaries

Ordering never bypasses lifecycle affinity. Work sharing an execution scope stays on the lane that claimed that scope. In remote mode, each worker sees only work compatible with its registered adapter and, for JUnit Platform tasks, the task's required engine. If no registered worker can execute a task, ScenarioMesh fails instead of assigning it to an incompatible worker.

ScenarioMesh does not currently apply heuristic worker-speed weights. Work-conserving dynamic assignment naturally lets faster workers consume more eligible tasks without inventing a hardware score that could change test semantics.
