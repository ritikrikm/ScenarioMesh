# ADR: Maven semantic ownership is capability-based and fail-closed

## Status

Accepted.

## Context

Transparent `mvn test` / `mvn verify` takeover is safe only when ScenarioMesh reproduces every execution-affecting Surefire/Failsafe semantic involved in that invocation. Treating configuration as a blacklist of known hazards does not scale: new plugin versions add parameters and user properties, while some existing parameters have complex precedence and provider-specific behavior.

## Decision

Every effective Surefire/Failsafe setting is classified as one of:

- `REPLACED_BY_SCENARIOMESH` — native concurrency/launch behavior intentionally replaced by the ScenarioMesh worker model.
- `PRESERVED` — ScenarioMesh reproduces the setting exactly or passes the effective value to the target runtime.
- `REQUIRES_CAPABILITY` — takeover is allowed only after the named semantic capability is implemented and proven.
- `UNKNOWN` — fail closed to native Maven.

Command-line user properties are part of the effective executor model, not generic JVM flags. ScenarioMesh must reproduce their Maven precedence before suppressing native execution.

Currently proven examples include:

- Surefire/Failsafe `<includes>` and `<excludes>` in ScenarioMesh's proven Maven glob/regex subset.
- `surefire.includes`, `surefire.excludes`, `failsafe.includes`, and `failsafe.excludes` user properties in the same proven subset.
- `systemPropertyVariables` with Maven user properties (`-D`) taking precedence on collisions, matching Surefire/Failsafe provider-property ordering.

Complex selectors such as `-Dtest=Class#method`, inline negation that has not been modeled, provider-specific dependency scanning, and unknown future options remain pass-through until exact equivalence is proven. Surefire TestNG `suiteXmlFiles` are supported as atomic native TestNG lifecycle scopes, and documented JUnit Platform `configurationParameters` are preserved using Java-properties syntax.

## Invariant

```text
Can ScenarioMesh prove the effective Maven semantics exactly?
  YES -> prepare runtime ownership, then suppress only the replaced executor
  NO  -> leave native Maven unchanged
```

## Fitness functions

For every semantic moved from pass-through to owned:

1. Native Maven and ScenarioMesh select the same test set.
2. Effective test-JVM properties have the same values and precedence.
3. Failure/skip behavior and lifecycle phase outcome match.
4. Unsupported grammar still produces native pass-through rather than a best-effort interpretation.
5. The test runs across the supported Maven/JDK matrix.
