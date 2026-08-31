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

System-property configuration is owned as one capability rather than as independent map merges. For each Surefire execution or each active Failsafe integration-test execution, ScenarioMesh resolves the documented source ordering from lowest to highest precedence:

```text
deprecated systemProperties
        ↓
systemPropertiesFile
        ↓
systemPropertyVariables
        ↓
Maven session user properties (-D), when promotion is enabled
        ↓
effective executor/worker system properties
```

Plugin-level and execution-level configuration are composed within each source category before the higher-precedence categories are applied. Maven user properties are promoted once, at the end, so a later execution layer cannot accidentally beat a command-line `-D` value. A scalar execution-level `systemPropertiesFile` replaces a plugin-level file, while map-like `systemPropertyVariables` retain non-overridden plugin entries.

`promoteUserPropertiesToSystemProperties=false` suppresses the final promotion step. Version-sensitive parameters are accepted only when their selected Surefire/Failsafe version supports them; unresolved or older versions remain fail-closed where equivalence cannot be established. Missing/unreadable property files, invalid Java-properties syntax, unresolved Maven expressions, and structurally unsupported legacy property declarations likewise remain native Maven pass-through.

Provider configuration such as JUnit Platform `configurationParameters` is not treated as another arbitrary Maven system-property source. It keeps its provider-specific preservation path so future provider semantics can evolve without changing the executor system-property precedence contract.

Properties documented by Surefire/Failsafe as VM-startup-sensitive are tracked explicitly. ScenarioMesh must not silently reinterpret a provider property as an `argLine`/JVM-launch property merely because the key is known to affect VM startup; launch-time equivalence must be proven separately.

Currently proven examples include:

- Surefire/Failsafe `<includes>` and `<excludes>` in ScenarioMesh's proven Maven glob/regex subset.
- `surefire.includes`, `surefire.excludes`, `failsafe.includes`, and `failsafe.excludes` user properties in the same proven subset.
- deprecated `<systemProperties>`, `<systemPropertiesFile>`, and `<systemPropertyVariables>` with their documented source precedence.
- Maven session user properties (`-D`) promoted at the documented highest system-property precedence unless promotion is explicitly disabled.

Complex selectors such as `-Dtest=Class#method`, inline negation that has not been modeled, provider-specific dependency scanning, and unknown future options remain pass-through until exact equivalence is proven. Surefire TestNG `suiteXmlFiles` are supported as atomic native TestNG lifecycle scopes, and documented JUnit Platform `configurationParameters` are preserved using Java-properties syntax.

## Invariant

```text
Can ScenarioMesh prove the effective Maven semantics exactly?
  YES -> prepare runtime ownership, then suppress only the replaced executor
  NO  -> leave native Maven unchanged
```

## Extension rule

A new Surefire/Failsafe property mechanism must not be added directly to an analyzer map. It must either:

1. extend the common effective-system-property capability with a documented precedence position, version boundary, and contract tests, or
2. remain `REQUIRES_CAPABILITY` / `UNKNOWN` and pass through.

This keeps future Maven/Surefire changes from becoming silent behavior changes in ScenarioMesh.

## Fitness functions

For every semantic moved from pass-through to owned:

1. Native Maven and ScenarioMesh select the same test set.
2. Effective test-JVM properties have the same values and precedence.
3. Failure/skip behavior and lifecycle phase outcome match.
4. Unsupported grammar still produces native pass-through rather than a best-effort interpretation.
5. The test runs across the supported Maven/JDK matrix.
6. Version-specific parameters are covered at both the first supported version boundary and at least one older fail-closed boundary.
