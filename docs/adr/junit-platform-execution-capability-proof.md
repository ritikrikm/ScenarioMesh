# ADR: JUnit Platform execution-capability proof

Status: accepted.

## Context

ScenarioMesh previously treated a small set of JUnit Platform engine IDs as globally ownable. That
was too coarse. An adapter can understand an engine family without proving every version, discovery
shape, nested suite composition, or lifecycle strategy for that engine.

The Cucumber JUnit Platform hardening exposed the problem clearly: engine detection was correct, but
ownership still depended on the exact selectors and lifecycle replay used by the target repository.

## Decision

JUnit Platform ownership is granted by an execution-capability profile, not by engine ID alone.

The selected-JVM probe now requires all of the following evidence before taking ownership:

1. the active ScenarioMesh adapter explicitly declares the engine;
2. the runtime engine implementation matches the expected framework identity;
3. the runtime engine major-version family is within a proven compatibility family;
4. discovery produced executable leaves through a selector shape ScenarioMesh supports;
5. the engine maps to a concrete ScenarioMesh execution strategy;
6. for `junit-platform-suite`, every nested engine visible in the discovered plan also has a proven
   engine identity and adapter contract.

The current profiles are:

- `jupiter-scoped-v1` for JUnit Jupiter 5.x and 6.x using Maven-selected class discovery and
  lifecycle-scoped replay;
- `vintage-scoped-v1` for JUnit Vintage 5.x and 6.x using Maven-selected class discovery and
  lifecycle-scoped replay;
- `cucumber-uniqueid-set-v1` for the official Cucumber JUnit Platform engine 7.x using a real class,
  suite, or classpath-resource discovery path and one Launcher execution containing the selected
  Cucumber Unique IDs;
- `platform-suite-scoped-v1` for JUnit Platform Suite Engine 1.x and 6.x when the suite is selected
  by class and all nested engines have proven contracts.

An unknown engine, spoofed engine identity, unsupported major version, unsupported selector shape, or
suite containing an unproven child engine remains native Maven pass-through.

Minor and patch versions stay within their framework major-version compatibility family but are still
subject to runtime discovery-shape and nested-engine proof. A new major version must be added only
after native-equivalence coverage demonstrates the execution contract.

## Consequences

- Adapter support and semantic ownership are now separate concepts.
- Adding an engine ID to `AdapterCapabilities` can no longer silently make an arbitrary engine
  ownable.
- Suite ownership composes only from proven child-engine contracts instead of assuming every engine
  inside a suite is safe.
- Failure remains fail-closed: unproven execution semantics retain native Surefire/Failsafe behavior.
- The proof reason records the matched execution profile, which makes preflight decisions easier to
  diagnose in CI and real repositories.

## Compatibility rule

The governing rule remains:

`native Maven semantics -> prove equivalent execution capability -> ScenarioMesh ownership`

If any required proof is missing, ScenarioMesh does not approximate the behavior.
