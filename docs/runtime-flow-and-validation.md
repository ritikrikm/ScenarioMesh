# ScenarioMesh Runtime Flow and Validation

This document describes the **current implemented behavior** on `main`. It is intentionally exact: when a check is not implemented, this document does not claim that it is.

## Complete flow

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ TARGET REPOSITORY                                                            │
│                                                                              │
│ Developer runs the normal command, for example:                             │
│                                                                              │
│                               mvn test                                       │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 1. MAVEN + SCENARIOMESH EXTENSION                                            │
│                                                                              │
│ Maven builds its normal project model. ScenarioMesh extension evaluates the  │
│ Maven invocation BEFORE it suppresses Surefire/Failsafe.                     │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 2. COMPATIBILITY / OWNERSHIP GATE                                            ║
║                                                                              ║
║ Question: "Can ScenarioMesh safely own this Maven test execution?"           ║
║                                                                              ║
║ Exact checks currently performed:                                            ║
║                                                                              ║
║ A. Maven goal must reach test lifecycle. Accepted phase/goal signals include:║
║    test, prepare-package, package, pre-integration-test, integration-test,   ║
║    post-integration-test, verify, install, deploy, or a goal ending in       ║
║    :test / :verify.                                                          ║
║                                                                              ║
║ B. Project must NOT explicitly skip tests through:                           ║
║    skipTests=true                                                            ║
║    maven.test.skip=true                                                      ║
║                                                                              ║
║ C. At least one supported framework owner must be visible in Maven model.    ║
║                                                                              ║
║    junit-platform owner is signaled by one or more of:                       ║
║      org.junit.jupiter:*                                                     ║
║      org.junit.platform:junit-platform-engine                                ║
║      org.junit.platform:junit-platform-launcher                              ║
║      org.junit.platform:junit-platform-suite-engine                          ║
║      io.cucumber:cucumber-junit-platform-engine                              ║
║                                                                              ║
║    cucumber-junit4 owner is signaled by:                                     ║
║      io.cucumber:cucumber-junit                                              ║
║      info.cukes:cucumber-junit                                               ║
║                                                                              ║
║    testng owner is signaled by:                                              ║
║      org.testng:testng                                                       ║
║                                                                              ║
║ D. Generic JUnit 4 is supported through JUnit Vintage when the target        ║
║    runtime supplies that engine. Without Vintage, or for an unproven custom  ║
║    JUnit 4 runner, native Maven remains the owner.                           ║
║                                                                              ║
║ E. JUnit Platform can own compatible Jupiter, Vintage, and Cucumber engines ║
║    together. Separate adapters still require complete, unambiguous suite     ║
║    ownership; for example JUnit Platform + standalone TestNG passes through.║
║                                                                              ║
║ F. Supported Surefire/Failsafe selection, group, suite XML, dependency-scan, ║
║    fork-launch, and rerun semantics are taken over only when their exact     ║
║    compatibility capability proves equivalence.                              ║
║                                                                              ║
║ G. Maven lifecycle execution plan must be determinable safely.               ║
║                                                                              ║
║ H. Unknown, custom, or unsupported Surefire/Failsafe configuration causes    ║
║    pass-through; ScenarioMesh never approximates an execution setting.       ║
║                                                                              ║
║ I. Surefire/Failsafe plugin configuration is analyzed. If ScenarioMesh       ║
║    cannot reproduce the relevant semantics safely, it does NOT take over.    ║
║                                                                              ║
║ RESULT                                                                       ║
║                                                                              ║
║       incompatible / uncertain              compatible                       ║
║                  │                              │                             ║
║                  ▼                              ▼                             ║
║       native Maven remains intact       ScenarioMesh takeover                ║
║       Surefire/Failsafe not suppressed  ownership may proceed                ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 3. ADAPTER PROBING + SELECTION                                               ║
║                                                                              ║
║ ScenarioMesh currently ships exactly THREE adapters:                         ║
║                                                                              ║
║   1. junit-platform                                                          ║
║   2. cucumber-junit4                                                         ║
║   3. testng                                                                  ║
║                                                                              ║
║ Current combinations:                                                        ║
║                                                                              ║
║   JUnit 5 / JUnit Platform tests                                             ║
║        └─> junit-platform adapter                                             ║
║                                                                              ║
║   Cucumber using cucumber-junit-platform-engine                              ║
║        └─> junit-platform adapter                                             ║
║                                                                              ║
║   Cucumber using JUnit 4 runner                                               ║
║        └─> cucumber-junit4 adapter                                            ║
║                                                                              ║
║   TestNG @Test methods                                                        ║
║        └─> testng adapter                                                     ║
║                                                                              ║
║   Generic JUnit 4 with JUnit Vintage                                           ║
║        └─> junit-platform adapter                                              ║
║                                                                              ║
║ AUTO mode behavior:                                                          ║
║                                                                              ║
║   • Every registered adapter is availability-probed.                         ║
║   • Every available adapter is asked to discover executable tasks.           ║
║   • Any availability/discovery error makes auto-detection fail safely.        ║
║   • 0 adapters with executable tasks => FAIL discovery.                       ║
║   • exactly 1 adapter with executable tasks => SELECT it.                     ║
║   • >1 adapters with executable tasks => FAIL as ambiguous.                   ║
║                                                                              ║
║ Explicit adapter behavior:                                                   ║
║                                                                              ║
║   execution.adapter=<id>                                                      ║
║                                                                              ║
║   • configured adapter must be registered.                                   ║
║   • if it discovers tasks, it is used.                                        ║
║   • if it discovers none:                                                     ║
║       adapterMismatchPolicy=fail => FAIL                                      ║
║       adapterMismatchPolicy=use-detected => switch ONLY when exactly one      ║
║       other adapter uniquely discovers executable tests and there were no     ║
║       auto-discovery errors.                                                  ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 4. DISCOVERY RUNS IN A SEPARATE JAVA PROCESS                                 │
│                                                                              │
│ ScenarioMesh launches DiscoveryMain with:                                    │
│                                                                              │
│   • target runtime classpath                                                  │
│   • effective JVM args                                                        │
│   • effective system properties                                               │
│   • configured adapter / auto                                                 │
│   • adapter mismatch policy                                                   │
│   • target test roots                                                         │
│   • include class-name regexes                                                │
│   • exclude class-name regexes                                                │
│                                                                              │
│ Exact process checks:                                                         │
│                                                                              │
│   • discovery must finish before discovery.timeout                            │
│   • default discovery.timeout = 2 minutes                                     │
│   • timeout => process is forcibly destroyed and run fails                    │
│   • non-zero discovery process exit => run fails                              │
│   • output is deserialized from discovered-scenarios.json                     │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 5. DISCOVERY INVARIANT VALIDATION                                            ║
║                                                                              ║
║ Coordinator validates the discovered workload BEFORE workers execute it.     ║
║                                                                              ║
║ Exact checks:                                                                ║
║                                                                              ║
║   • selected adapter IDs must not contain duplicates                         ║
║   • every ScenarioTask.adapterId must reference a selected adapter            ║
║   • every ScenarioTask.selector must be non-blank                             ║
║   • every ScenarioTask.displayName must be non-blank                          ║
║   • ScenarioTask IDs must be unique                                           ║
║   • adapterId + selector pairs must be unique                                 ║
║                                                                              ║
║ Any violation => IllegalStateException => execution does not continue.        ║
║                                                                              ║
║ Cucumber/JUnit Platform duplicate protection also exists inside its adapter.  ║
║ The coordinator invariant is the final generic safety boundary.               ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 6. WORKER CONFIGURATION                                                      ║
║                                                                              ║
║ Current defaults:                                                            ║
║                                                                              ║
║   enabled                         = true                                      ║
║   execution.adapter               = auto                                      ║
║   adapterMismatchPolicy           = fail                                      ║
║   execution.infrastructureRetries = 0                                         ║
║   workers.count                   = 4                                         ║
║   workers.minimumReady            = 4                                         ║
║   workers.maxTasksPerWorker       = 0  (task-count recycling disabled)        ║
║   workers.maxHeapUsagePercent     = 0  (heap recycling disabled)              ║
║   discovery.timeout               = 2 minutes                                 ║
║   workers.startupTimeout          = 30 seconds                                ║
║   workers.taskTimeout             = 15 minutes                                ║
║   workers.shutdownTimeout         = 10 seconds                                ║
║   reporting.directory             = <buildDirectory>/scenariomesh             ║
║   liveConsoleLogs                 = true                                      ║
║   workerLogFiles                  = true                                      ║
║   showConfiguration               = true                                      ║
║   showProgress                    = true                                      ║
║                                                                              ║
║ Config validation:                                                           ║
║                                                                              ║
║   • workers.count > 0                                                       ║
║   • workers.minimumReady between 1 and workers.count                         ║
║   • infrastructureRetries >= 0                                               ║
║   • maxTasksPerWorker >= 0                                                   ║
║   • maxHeapUsagePercent between 0 and 100                                    ║
║   • discovery timeout > 0                                                    ║
║   • startup/task/shutdown timeout > 0                                        ║
║   • socket-based timeouts <= Integer.MAX_VALUE milliseconds                  ║
║   • reporting.directory is required                                          ║
║   • execution.adapter cannot be blank                                         ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 7. ISOLATED WORKER JVM POOL                                                  │
│                                                                              │
│ Default: four separate worker JVM processes.                                 │
│                                                                              │
│       Coordinator                                                            │
│           │                                                                  │
│      ┌────┼────┬────┐                                                       │
│      ▼    ▼    ▼    ▼                                                       │
│     JVM1 JVM2 JVM3 JVM4                                                      │
│                                                                              │
│ They are processes, not four test threads sharing one JVM.                   │
│ This isolates static state, WebDriver/static framework state, and failures.  │
│                                                                              │
│ Workers must become READY before being usable.                               │
│ A started OS process alone is not treated as a ready worker.                 │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 8. DYNAMIC FIFO SCHEDULING                                                   │
│                                                                              │
│ All discovered tasks enter one central queue.                                │
│                                                                              │
│ ScenarioMesh does NOT hard-partition 500 tasks as 125 per worker.            │
│                                                                              │
│ READY/available worker => receives next queued task.                         │
│ Fast worker finishes sooner => receives another task sooner.                 │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 9. RESULT / PROTOCOL VALIDATION                                              ║
║                                                                              ║
║ A worker RESULT is not trusted blindly. For each dispatched task,             ║
║ ScenarioMesh validates:                                                      ║
║                                                                              ║
║   envelope.protocolVersion == Protocol.VERSION                               ║
║   envelope.type == RESULT                                                    ║
║   envelope.workerId == expected worker                                       ║
║   envelope.attempt == expected attempt                                       ║
║   envelope.result != null                                                    ║
║   envelope.error is null/blank                                               ║
║                                                                              ║
║ Inside ExecutionResult:                                                      ║
║                                                                              ║
║   result.scenarioId == dispatched task.id                                    ║
║   result.displayName == dispatched task.displayName                          ║
║   result.workerId == expected worker                                         ║
║   result.attempt == expected attempt                                         ║
║   result.attempt >= 1                                                        ║
║   result.duration is not negative                                            ║
║   result.finishedAt >= result.startedAt                                      ║
║   result.startedAt is not before the dispatch window                         ║
║                                                                              ║
║ Any violation is converted into an INFRASTRUCTURE_FAILURE result with        ║
║ failure type ProtocolResultValidationFailure.                                ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ 10. FINAL COMPLETENESS / DUPLICATE GATE                                      ║
║                                                                              ║
║ After worker execution:                                                      ║
║                                                                              ║
║   • result scenario ID must belong to discovered task IDs                    ║
║   • a discovered task may have only ONE terminal result                      ║
║   • a second terminal result for same task => FAIL immediately               ║
║   • a terminal result for an undiscovered task => FAIL immediately           ║
║                                                                              ║
║ For every discovered task without a terminal worker result, ScenarioMesh     ║
║ explicitly creates an INFRASTRUCTURE_FAILURE:                                ║
║                                                                              ║
║   workerId   = coordinator                                                   ║
║   failureType= MissingResult                                                 ║
║   message    = "No worker produced a terminal result for this task"          ║
║                                                                              ║
║ Therefore a missing task cannot silently disappear from the final outcome.   ║
╚════════════════════════════════════╤═════════════════════════════════════════╝
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 11. REPORT + MAVEN OUTCOME                                                   │
│                                                                              │
│ ScenarioMesh reports discovered/pass/skip/fail counts and writes its output  │
│ under reporting.directory. Maven receives the resulting success/failure      │
│ semantics rather than a worker-process-only success signal.                  │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 12. WORKER SHUTDOWN                                                          │
│                                                                              │
│ WorkerPool is used in try-with-resources. The pool is closed after execution │
│ even when execution throws. Worker shutdown timeout default = 10 seconds.    │
└────────────────────────────────────┬─────────────────────────────────────────┘
                                     │
                                     ▼
╔══════════════════════════════════════════════════════════════════════════════╗
║ FINAL BUILD DECISION                                                         ║
║                                                                              ║
║ Correctness is NOT "Maven process exited".                                   ║
║ Correctness means the discovered workload is accounted for exactly once.     ║
║                                                                              ║
║ Our external 500-scenario validation produced:                               ║
║                                                                              ║
║   expected   = 500                                                           ║
║   discovered = 500                                                           ║
║   executed   = 500                                                           ║
║   unique     = 500                                                           ║
║   missing    = 0                                                             ║
║   duplicates = 0                                                             ║
║   failed     = 0                                                             ║
║                                                                              ║
║                           BUILD SUCCESS                                      ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## Important failure examples

```text
500 discovered / 499 worker results
    -> missing task becomes explicit MissingResult infrastructure failure

500 expected / 1000 executions
    -> duplicate execution is incorrect and must not be accepted

same ScenarioTask ID appears twice during discovery
    -> discovery invariant violation

same adapter + same selector appears twice
    -> discovery invariant violation

worker returns result for another scenario ID
    -> ProtocolResultValidationFailure

worker returns result with wrong attempt or worker ID
    -> ProtocolResultValidationFailure

0 adapters discover executable tests in auto mode
    -> discovery fails; ScenarioMesh does not guess

2 adapters discover executable tests in auto mode
    -> ownership is ambiguous; discovery fails

unsupported Maven selection semantics detected
    -> ScenarioMesh does not take over; native Maven behavior is preserved
```

## One-line mental model

```text
mvn test
  -> compatibility gate
  -> adapter probe/selection
  -> isolated discovery process
  -> discovery invariants
  -> isolated worker JVMs
  -> central FIFO queue
  -> per-dispatch protocol/result validation
  -> exact-once completeness validation
  -> report
  -> worker cleanup
  -> Maven success/failure
```

The most important design rule is: **ScenarioMesh must prefer pass-through or explicit failure over silently running the wrong set of tests.**
