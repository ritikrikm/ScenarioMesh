# scenariomesh-maven-plugin

Maven plugin entry points for explicit ScenarioMesh operations such as running coordinator/worker functionality from Maven.

## Role

This module translates Maven project/runtime information into ScenarioMesh inputs and exposes Maven goals used by explicit execution, worker startup, diagnostics, and integration testing.

It is different from the Maven core extension: the plugin provides explicit goals; the extension is what can observe normal lifecycle execution early enough to support transparent `mvn test` takeover.

## Typical explicit flow

```text
mvn scenariomesh:run / :worker / related goal
        ↓
resolve effective Maven project + test runtime
        ↓
construct ScenarioMesh request
        ↓
coordinator or worker runtime
```

Do not duplicate ownership/preflight semantics inside individual Mojos. Maven-facing code should delegate to shared runtime logic so explicit and transparent execution do not evolve into different products.
