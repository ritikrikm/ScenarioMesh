# ADR: P2 Maven execution semantics remain native unless topology is reproducible

## Status

Accepted.

## Context

ScenarioMesh replaces Surefire/Failsafe process execution with independently
scheduled worker JVMs. Some Maven executor options are not merely input values;
they are defined by mutable Surefire state or by the native fork topology. A
best-effort reimplementation would make `mvn test` behave differently.

## Decision

ScenarioMesh owns a setting only when it can prove the same selected tests,
lifecycle behavior, process context, result accounting, and Maven exit result.
Otherwise it retains native Maven execution and prints the exact reason.

| Semantic | Product decision |
|---|---|
| `filesystem`, alphabetical, reverse-alphabetical, seeded random `runOrder` | Owned when the effective order is representable. |
| `failedfirst`, `balanced` | Native Maven pass-through. Surefire defines both through its persistent `.surefire-*` statistics-file lifecycle. |
| `skipAfterFailureCount` | Native Maven pass-through. Surefire documents race-prone behavior under parallel/forked execution, so a global ScenarioMesh stop barrier would not be equivalent. |
| `${surefire.forkNumber}` / `@{surefire.forkNumber}` | Native Maven pass-through wherever found in selected executor configuration. Surefire assigns it from actual forks and Maven `-T` topology. |
| Known JUnit Platform TestEngine plugin dependencies | Eligible after exact classpath resolution and runtime ownership preflight. |
| Unknown provider/plugin dependencies or provider properties | Native Maven pass-through. Maven public APIs do not expose enough provider lifecycle semantics for ScenarioMesh to substitute them safely. |
| Surefire/Failsafe selector grammar | Delegate to Surefire's public `TestListResolver`; never maintain a second parser. |

The fork-number guard scans both plugin and selected execution configuration
before Maven interpolation can hide the placeholder. This covers documented
`argLine`, environment-variable, and test-system-property uses.

## Future architecture

The correct expansion path for stateful providers is a **native provider
capsule**: let Surefire/Failsafe launch and own the provider/fork topology, then
exchange reportable work and results with ScenarioMesh at a boundary whose
native equivalence is tested. Do not reverse-engineer private `.surefire-*`
formats or emulate custom providers inside the scheduler.

## Maven 4 qualification

Maven 4.0.0-rc-6 remains a preview gate. Apache Maven has not released Maven
4 GA. When GA is published, pin that exact version, run the complete Maven 4
matrix on Java 17, 21, and 25, compare the representative native/takeover
fixtures, and only then update the production support claim.

## References

- [Surefire `test` goal parameters](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo)
- [Surefire fork options and parallel execution](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)
- [Apache Maven release history](https://maven.apache.org/docs/history.html)
