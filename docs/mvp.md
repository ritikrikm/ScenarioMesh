# Current product scope

## In scope

- automatic activation from normal Maven lifecycle commands after one-time extension installation
- default four isolated local JVM workers for one Maven run
- history-aware longest-processing-time-first scheduling by default, with deterministic FIFO fallback for cold tasks and an explicit strict-FIFO option
- JUnit Platform discovery/execution for the proven JUnit 5 and JUnit 6 lines
- Cucumber JUnit Platform execution for the proven Cucumber 7.x line
- generic JUnit 4 execution through JUnit Vintage when the target runtime supplies a proven Vintage engine
- legacy Cucumber JUnit 4 runner discovery/execution
- standard method-level TestNG tests
- TestNG `suiteXmlFiles` execution as atomic lifecycle scopes
- compatible Maven Surefire `test` and Failsafe `integration-test` / `verify` ownership, with native Maven pass-through when equivalence cannot be proven
- forwarding compatible Maven command-line `-D` properties and provider configuration to discovery/workers
- configurable worker count, timeouts, worker JVM args, and report directory
- JSON, JUnit XML, Surefire-style XML, HTML, events, and artifact-reference reporting
- correct Maven failure on test or infrastructure failures
- explicit opt-out
- shared distributed leases, heartbeats, stale-lease rejection, and authenticated remote worker execution
- worker crash retry/requeue for safe work
- CLI `init`, `doctor`, `run`, and `diagnostics`

## Explicitly deferred or native pass-through

- persistent daemon and worker reuse between Maven runs
- build-fingerprint stale-worker recycling beyond the current recycling gates
- remote Docker/Kubernetes worker orchestration
- Selenium Grid-aware scheduling
- report merging with every framework-specific third-party reporter
- framework major lines outside the proven execution contracts, including Cucumber 8.x
- unknown or unsupported JUnit Platform engine identities/major versions
- factory-heavy TestNG discovery without an explicit suite XML lifecycle scope
- Gradle integration

The product rule is fail-closed: a repository/runtime combination is owned only when ScenarioMesh can prove equivalent discovery, execution, lifecycle, outcome, and Maven semantics. Anything outside that proven envelope remains native Maven rather than being treated as partially supported.
