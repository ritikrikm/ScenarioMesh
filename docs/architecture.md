# ScenarioMesh architecture

## Current MVP boundary

The MVP is a complete vertical slice, not the final platform. It deliberately implements the pieces required to prove transparent Maven execution through report generation while leaving persistent daemon, historical scheduling, shared-resource leases, remote workers, build fingerprint recycling, and advanced crash recovery for later milestones.

## Dependency direction

`scenariomesh-core` owns typed domain objects and adapter/scheduler ports. Framework adapters depend on core. The coordinator depends on ports, protocol, scheduler, configuration, and worker runtime. Maven integration depends on the coordinator and reporting layer. Core never imports Maven, Cucumber, JUnit, TestNG, Selenium, sockets, or ProcessBuilder.

## Discovery

The JUnit Platform adapter uses the public Launcher/TestPlan/UniqueId APIs. Cucumber running on JUnit Platform is therefore discovered by its own engine. The legacy Cucumber/JUnit 4 adapter asks JUnit for the Cucumber runner's Description tree and schedules its leaf tests; it does not parse `.feature` files. TestNG MVP discovery is limited to standard method-level `@Test` classes.

## Isolation and scheduling

Each execution worker is a separate JVM. A worker executes one ScenarioTask at a time. Four workers are created by default for one Maven run. A thread-safe FIFO strategy owns the queue; coordinator worker loops ask the strategy for the next task whenever their current task completes. This makes the assignment dynamic rather than pre-sharding the test list.

## Worker protocol

Coordinator binds a `ServerSocket` to the JVM loopback interface with port `0`, allowing the operating system to allocate an available port. Workers authenticate their initial HELLO with a run-scoped random token. Protocol payloads are explicit Jackson JSON records and are versioned. Java native serialization is not used.

Worker stdout/stderr is redirected to per-worker logs. The control protocol therefore cannot be corrupted by target-project logging.

## Maven lifecycle

A Maven Core Extension is installed once through `.mvn/extensions.xml`. During `afterProjectsRead`, it injects the ScenarioMesh Maven plugin into non-POM projects for the `test` phase and sets Maven's normal test execution to skip for that project while ScenarioMesh is enabled. The ScenarioMesh goal still runs because it does not use Surefire's skip flag.

`-Dscenariomesh.enabled=false` causes the extension to make no lifecycle modifications, preserving the repository's normal Maven behavior.

## Reports and correctness

Every discovered task must end with a typed result. Missing terminal results are converted to infrastructure failures instead of disappearing. Scenario assertion failures are `TEST_FAILURE`; worker/control failures are `WORKER_FAILURE`/`INFRASTRUCTURE_FAILURE`. The Maven goal fails when any terminal result is non-passing.
