# scenariomesh-scheduler

This module decides which eligible work unit should run next.

## What it does

ScenarioMesh uses dynamic assignment rather than statically splitting the test suite into N fixed chunks. When a worker becomes available, the scheduler chooses the next eligible unit.

Supported ordering includes strict FIFO and duration-history/LPT-style scheduling. Execution history may improve ordering, but it must never change test identity or lifecycle semantics.

## Flow

```text
discovered lifecycle-safe work units
        ↓
optional historical durations
        ↓
scheduler ordering
        ↓
worker-specific eligibility filter
        ↓
next work unit
```

## Important boundary

The scheduler orders work; it does not prove that a worker can execute it. Remote adapter/engine capability checks and lifecycle affinity remain authoritative outside the ordering policy.

Likewise, scheduling must not split a lifecycle-scoped unit merely to create more parallelism. Correctness wins over worker utilization.
