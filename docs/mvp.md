# MVP scope

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

## Explicitly deferred

- persistent daemon and worker reuse between Maven runs
- build-fingerprint stale-worker recycling
- duration history/LPT scheduling
- shared-resource capacities/leases
- worker crash retry/requeue
- remote/Docker/Kubernetes workers
- Selenium Grid-aware scheduling
- report merging with every framework-specific third-party reporter
- generic non-Cucumber JUnit 4
- advanced TestNG XML/factory discovery

The deferred features have extension seams in the core design but are not claimed to work in the MVP.
