# scenariomesh-coordinator

The coordinator owns a ScenarioMesh run after compatibility has already been proven.

It is the central orchestration module: it turns discovered tasks into executable work units, prepares local or remote workers, validates capabilities, manages leases/liveness, asks the scheduler for eligible work, validates results, and drives run completion.

## High-level flow

```text
owned RunRequest + discovered tasks
        ↓
validate lifecycle/work-unit structure
        ↓
prepare worker pool
        ↓
prove aggregate adapter/engine capability coverage
        ↓
assign each worker only eligible work
        ↓
create authoritative lease
        ↓
monitor heartbeat / crash / replacement
        ↓
validate terminal result against work + lease
        ↓
collect all terminal outcomes
        ↓
reporting + Maven result
```

## Distributed behavior

Remote workers register through the versioned protocol and advertise runtime capabilities. Heterogeneous fleets are allowed: not every worker needs every adapter, but every required task must have at least one worker that can run the complete adapter+engine combination.

Prepared workers are important to transparent Maven takeover: ScenarioMesh should not suppress native execution first and hope usable workers arrive later.

## Correctness rule

Missing, stale, duplicate, mismatched, or unowned results are infrastructure problems, not successful test results. Lifecycle-scoped uncertain work must not be automatically replayed unless the semantics prove retry is safe.
