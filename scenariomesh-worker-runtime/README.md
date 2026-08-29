# scenariomesh-worker-runtime

This module is the isolated JVM process that actually executes ScenarioMesh work.

## Why workers are processes

ScenarioMesh intentionally isolates workers in separate JVMs rather than using only threads in the coordinator. This separates heap, static state, framework globals, classloaders, browser/test process state, and failures between execution lanes.

## Worker lifecycle

```text
start worker JVM with target test runtime
        ↓
connect/register with coordinator
        ↓
advertise Java/runtime/adapter/engine capabilities
        ↓
receive RUN work unit + authoritative lease
        ↓
execute through selected framework adapter
        ↓
emit heartbeats while owned
        ↓
return structured terminal result
        ↓
accept more work or DRAIN/STOP
```

A worker process currently represents one execution lane. Do not introduce hidden concurrency inside a worker merely because the host has spare CPU.

## Safety

Authentication/TLS secrets must not be exposed as command-line arguments. Cleanup hooks and process-tree termination are best-effort safeguards for child browser/driver/test processes. Scoped lifecycle work is not blindly retried after uncertain execution because setup/teardown side effects may already have happened.
