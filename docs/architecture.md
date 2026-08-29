# ScenarioMesh architecture

## Current product boundary

The original minimal slice has grown into the current product surface. ScenarioMesh now includes transparent Maven execution, distributed worker ownership, negotiated protocol compatibility, history-aware scheduling, reporting, diagnostics, and CLI tooling. The remaining roadmap still covers daemonization, deeper recycling policies, advanced orchestration targets, and broader framework coverage.

## Dependency direction

`scenariomesh-core` owns typed domain objects and adapter/scheduler ports. Framework adapters depend on core. The coordinator depends on ports, protocol, scheduler, configuration, and worker runtime. Maven integration depends on the coordinator and reporting layer. Core never imports Maven, Cucumber, JUnit, TestNG, Selenium, sockets, or ProcessBuilder.

## Discovery

The JUnit Platform adapter uses the public Launcher/TestPlan/UniqueId APIs. Cucumber running on JUnit Platform is therefore discovered by its own engine. The legacy Cucumber/JUnit 4 adapter asks JUnit for the Cucumber runner's `Description` tree and schedules executable leaf tests; it does not parse `.feature` files and it does not assume one runner equals one scenario. A generated runner is treated as an execution container. If different runners expose the same framework description identity, the product fails discovery as ambiguous rather than silently duplicating or dropping execution. TestNG classpath discovery is limited to safe method-level `@Test` classes. Explicit TestNG suite XML files are instead executed as atomic lifecycle scopes by TestNG itself, preserving suite parameters, groups, listeners, factories, dependencies, and configuration hooks before ScenarioMesh materializes the resulting test outcomes.

## Isolation and scheduling

Each execution worker is a separate JVM. A worker executes one ScenarioTask at a time. Four workers are created by default for one Maven run. A thread-safe FIFO strategy owns the queue; coordinator worker loops ask the strategy for the next task whenever their current task completes. This makes the assignment dynamic rather than pre-sharding the test list.

## Worker protocol

Coordinator binds a `ServerSocket` to the JVM loopback interface with port `0`, allowing the operating system to allocate an available port. Workers authenticate their initial HELLO with a run-scoped random token. Protocol payloads are explicit Jackson JSON records and are versioned. Java native serialization is not used.

Worker stdout/stderr is redirected to per-worker logs. The control protocol therefore cannot be corrupted by target-project logging.

## Maven lifecycle

A Maven Core Extension is installed once through `.mvn/extensions.xml`. During `afterProjectsRead`, it injects the ScenarioMesh Maven plugin into non-POM projects for the `test` phase and sets Maven's normal test execution to skip for that project while ScenarioMesh is enabled. The ScenarioMesh goal still runs because it does not use Surefire's skip flag.

Compatibility is evaluated against the requested lifecycle, not merely against plugin presence. For example, a normal Failsafe execution bound to `integration-test`/`verify` does not block a plain `mvn test`, because those phases are not reached. The same Failsafe execution is relevant to `mvn verify`, and ScenarioMesh currently passes through because integration-test lifecycle equivalence is not yet implemented. A custom Failsafe execution bound unusually to `test`, or an execution whose phase cannot be established, also remains pass-through. Unknown behavior is conservative by design.

`-Dscenariomesh.enabled=false` causes the extension to make no lifecycle modifications, preserving the repository's normal Maven behavior.

## Reports and correctness

Every discovered task must end with a typed result. Missing terminal results are converted to infrastructure failures instead of disappearing. Scenario assertion failures are `TEST_FAILURE`; worker/control failures are `WORKER_FAILURE`/`INFRASTRUCTURE_FAILURE`. The Maven goal fails when any terminal result is non-passing.
