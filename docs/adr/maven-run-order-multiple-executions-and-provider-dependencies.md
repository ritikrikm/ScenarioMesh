# ADR: Maven run order, multiple executions, and provider dependencies

Status: implemented capability boundary

## Context

ScenarioMesh may suppress native Surefire/Failsafe execution only when the effective Maven behavior can be reproduced without changing the selected test set, process context, provider semantics, ordering contract, or lifecycle outcome.

Three compatibility areas require special treatment because they influence execution outside an individual test invocation:

1. Surefire/Failsafe class `runOrder`.
2. Multiple Surefire executions bound to the Maven `test` phase.
3. Dependencies declared on the Surefire/Failsafe plugin itself.

The implementation must remain safe when future Maven, Surefire, JUnit Platform, or provider versions introduce new values or semantics.

## Decision 1: Maven class run order is separate from ScenarioMesh scheduling optimization

An explicit/native Maven class-order contract is applied before ScenarioMesh dispatch. ScenarioMesh duration-history/LPT scheduling must not silently reorder that contract.

The currently owned stateless modes are:

- `filesystem`: retain discovery admission order;
- `alphabetical`: order class buckets by class name;
- `reversealphabetical`: reverse class-name order;
- `random`: use the Surefire algorithm `Collections.shuffle(list, new Random(seed))`.

For `random`, the effective seed is resolved once by the Maven integration. A configured Surefire/Failsafe seed is preserved. When Maven did not supply one, ScenarioMesh uses the same seed source as current Surefire (`System.nanoTime()`) and carries that effective seed into coordinator ordering.

Internal ordering properties are coordinator controls and are removed before the target test JVM is launched.

### Stateful run order

`failedfirst` and `balanced` remain native Maven for now.

These modes depend on Surefire's persistent `.surefire-*` statistics-file read/update lifecycle and configuration-derived file identity. ScenarioMesh's own duration history is not considered equivalent. A configured statistics-file checksum alone is not enough to claim ownership because correct update semantics must also be reproduced.

Unknown future `runOrder` values also pass through.

## Decision 2: Multiple Surefire executions are independent execution plans

ScenarioMesh models each supported Maven Surefire `test` execution as an independent executor plan preserving Maven model order. Each plan retains its own:

- execution id;
- includes/excludes and command-line selection;
- zero-test policy;
- failure-ignore policy;
- run-order policy;
- fork/classpath configuration when independently provable.

The existing lifecycle injector creates one ScenarioMesh `run` execution per executor plan rather than combining selections into one test set.

### Fail-closed preflight boundary

A project-level ownership preflight must not suppress several native executions after proving only one materially different process/discovery context. Therefore, when more than one Surefire execution is present, ScenarioMesh currently owns only configurations whose execution differences are preflight-safe (primarily selection, failure policy, scheduling controls, and ScenarioMesh-replaced parallel/fork scheduling knobs).

Configuration that can change the test JVM, framework discovery, provider behavior, environment, working directory, system properties, suite files, or target classpath causes native Maven pass-through until a per-execution/aggregate preflight contract exists.

Maven 4 indexed phases such as `test[100]` are not flattened into ordinary `test`: their ordering relative to other lifecycle executions is not yet modeled, so they remain native Maven.

If a downstream report consumer exposes only one mutable input directory, multiple executor plans also remain native rather than implicitly merging distinct reports.

## Decision 3: plugin dependencies are classified by semantic role

A dependency on `maven-surefire-plugin` or `maven-failsafe-plugin` is not automatically a custom provider. Current Surefire/JUnit Platform usage legitimately permits TestEngine implementations to be declared as plugin dependencies.

ScenarioMesh therefore distinguishes recognized engine roots from unknown provider/plugin extensions.

Currently recognized engine roots are:

- `org.junit.jupiter:junit-jupiter-engine`;
- `io.cucumber:cucumber-junit-platform-engine`;
- `org.junit.platform:junit-platform-suite-engine`.

A recognized engine dependency must have an explicit version. It is resolved through Maven Resolver with transitive dependencies and added to ScenarioMesh's target test classpath so JUnit Platform discovery sees the same engine capability.

`org.junit.vintage:junit-vintage-engine` remains behind the dedicated JUnit 4/Vintage equivalence gate.

Every other plugin dependency is treated as potentially provider-semantic and causes native Maven pass-through. ScenarioMesh does not use artifact-name wildcards or assume an unknown dependency is harmless.

## Compatibility invariant

For all three areas:

```text
semantic value/shape is explicitly understood
        -> model it as typed ScenarioMesh execution state
        -> prove runtime ownership
        -> take over

semantic value/shape is unknown, stateful without exact persistence,
or requires a process/provider contract ScenarioMesh cannot prove
        -> native Maven pass-through
```

This is intentionally conservative. Adding support later means extending a focused semantic capability and its contract tests rather than weakening a global safety check.
