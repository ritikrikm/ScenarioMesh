# ScenarioMesh

ScenarioMesh is a process-isolated parallel execution runtime for Java test repositories. The architectural target is transparent `mvn test` activation while preserving framework-native discovery and lifecycle semantics.

> **Repository status:** this repository contains the compileable foundation (typed domain, configuration precedence, versioned JSON protocol, scheduling/resource ledger, local JVM launcher, fingerprinting and coordinator). The full Maven lifecycle extension, daemon persistence, Cucumber adapters and report merger are intentionally **not claimed as implemented** in this bootstrap revision. They require framework-version-specific integration tests before they can truthfully be called production-ready.

## Why processes?
Separate JVMs isolate static WebDriver fields, singletons, caches and other legacy mutable state. ScenarioMesh schedules scenarios; it does not replace Selenium or Cucumber.

## Runtime flow
`mvn test` → integration hook → framework-native discovery → fingerprint → compatible worker pool → dynamic scheduler → isolated worker JVMs → result aggregation → Maven result.

## Configuration precedence
One resolver owns precedence: **CLI > Maven/system property > environment > YAML/config file > Maven plugin configuration > defaults**. Operational defaults live in `ScenarioMeshConfig.defaults()`.

## Modules
- `scenariomesh-core`: immutable domain and extension ports
- `scenariomesh-config`: validated configuration and precedence
- `scenariomesh-protocol`: versioned JSON envelopes
- `scenariomesh-scheduler`: LPT-style duration-aware scheduling and resource leases
- `scenariomesh-worker-runtime`: local `ProcessBuilder` worker launcher
- `scenariomesh-coordinator`: fingerprinting and assignment orchestration
- `scenariomesh-cli`: diagnostic entry point

See `docs/architecture.md` and ADRs for decisions and remaining integration milestones.
