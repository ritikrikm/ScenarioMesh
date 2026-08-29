# Current product scope

## In scope

- automatic activation from normal Maven lifecycle commands after one-time extension installation
- default four isolated JVM workers
- dynamic FIFO assignment
- JUnit Platform discovery/execution, including native JUnit 5 and Cucumber JUnit Platform engine tests
- legacy Cucumber JUnit 4 runner discovery/execution
- standard method-level TestNG tests
- forwarding Maven command-line `-D` properties to discovery/workers
- configurable worker count, timeouts, worker JVM args, and report directory
- JSON, JUnit XML, and HTML reports
- correct Maven failure on test or infrastructure failures
- explicit opt-out
- duration-aware/LPT history scheduling with FIFO fallback
- shared distributed leases, heartbeats, and stale-lease rejection
- remote authenticated worker execution
- worker crash retry/requeue for safe leaf work
- CLI `init`, `doctor`, and `diagnostics`

## Explicitly deferred

- persistent daemon and worker reuse between Maven runs
- build-fingerprint stale-worker recycling beyond the current recycling gates
- remote/Docker/Kubernetes worker orchestration
- Selenium Grid-aware scheduling
- report merging with every framework-specific third-party reporter
- generic non-Cucumber JUnit 4
- advanced TestNG XML/factory discovery

The deferred features have extension seams in the current design but are not all claimed to be production-ready or generally supported yet.
