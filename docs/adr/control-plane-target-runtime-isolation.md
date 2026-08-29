# ADR: Control-plane and target-runtime dependency isolation

## Status

Accepted incrementally. The selected-JVM preflight result boundary is isolated now; full worker target-realm classloading is the next migration step.

## Context

ScenarioMesh executes inside repositories that can depend on arbitrary versions of Jackson, JUnit Platform, Cucumber, TestNG, SLF4J, Selenium, and other libraries. A flat JVM classpath lets whichever copy appears first control both ScenarioMesh internals and the target test runtime.

That violates two independent requirements:

1. ScenarioMesh control-plane behavior must not depend on target dependency versions.
2. Target discovery/execution must continue to use the target repository's intended framework versions.

Simply reversing classpath order satisfies only the first requirement and can silently violate the second.

## Decision

The target architecture is two classloader realms inside each selected test JVM/process:

```text
ScenarioMesh process
├── control realm
│   ├── core / protocol / worker lifecycle
│   ├── ScenarioMesh serialization and telemetry
│   └── transport/authentication
└── target execution realm
    ├── target classes and test classes
    ├── target JUnit/Cucumber/TestNG/Selenium dependencies
    └── ScenarioMesh adapter implementations bound to those target APIs
```

`io.scenariomesh.core` contracts shared across the bridge are parent-owned. Adapter implementations are loaded through the target runtime classloader so their framework API references resolve against the target realm. Control-plane libraries must never be resolved from the target realm.

As an immediate hardening step, the selected-JVM ownership probe uses a JDK-only result format. This permanently removes Jackson from the bootstrap proof boundary and is guarded by a hostile target fixture carrying Jackson 2.9.

## Consequences

- Target dependency conflicts can no longer be treated as ordinary classpath-order bugs.
- Adapter loading must remain compatible with a parent-owned `ScenarioAdapter` SPI and target-owned implementation classes.
- Protocol/control serialization and target framework execution must not share accidental third-party types across the realm boundary.
- Until the complete worker realm split is implemented, compatibility tests must continue to expose hostile target dependency combinations and ScenarioMesh must fail closed when ownership cannot be proven.

## Fitness functions

- A target with an incompatible/old Jackson version cannot break ScenarioMesh preflight.
- ScenarioMesh's own dependency versions cannot replace the target's JUnit/Cucumber/TestNG versions during discovery/execution.
- Built-in adapters can be resolved through the supplied runtime classloader while implementing the parent-owned SPI.
- Unknown linkage/classloading ambiguity causes pass-through or infrastructure failure, never silent semantic substitution.
