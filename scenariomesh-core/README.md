# scenariomesh-core

The core module contains the framework-neutral domain model and extension contracts used by the rest of ScenarioMesh.

## What belongs here

Core types describe concepts such as discovered tasks, execution results, lifecycle scope, runtime context, adapter contracts, worker cleanup hooks, reporting/artifact extension points, and structured runtime events.

This module is intentionally independent of Maven, JUnit, Cucumber, TestNG, sockets, and a particular scheduling algorithm. Code in higher-level modules should depend on these contracts instead of duplicating framework-specific versions of the same concept.

## Where it sits in the flow

```text
Maven/runtime inspection
        ↓
framework adapter discovers executable work
        ↓
CORE DOMAIN TYPES
  ScenarioTask / scope / result / extension contracts
        ↓
coordinator + scheduler + worker runtime
        ↓
reporting / observability
```

## Design rule

If a concept is part of ScenarioMesh's execution model but does not need to know *how* Maven, a test framework, transport, or report format implements it, it usually belongs here.

Core must stay small and stable because almost every other module depends on it.
