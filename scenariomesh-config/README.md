# scenariomesh-config

This module resolves ScenarioMesh configuration into one typed runtime configuration model.

## What it does

It handles supported configuration sources such as ScenarioMesh YAML, environment variables, JVM/system properties, Maven/plugin-provided values, and defaults. Precedence is resolved centrally so coordinator, Maven integration, CLI, and workers do not each invent their own configuration rules.

Typical settings include worker count, scheduler strategy, distributed-worker settings, lifecycle/recycling limits, reporting/diagnostics behavior, and transport/security options.

## Runtime role

```text
raw config sources
   ↓
validate + normalize + apply precedence
   ↓
resolved ScenarioMesh configuration
   ↓
preflight / coordinator / workers / reporting
```

## Important boundary

This module does not decide whether ScenarioMesh may own a Maven test execution. Configuration can enable or tune behavior, but compatibility and ownership still have to be proven by the Maven/runtime preflight path.

Do not add target-library switches for Selenium, REST Assured, Jackson, or company libraries merely because they are present in a test repository. Those normally arrive through the target Maven test runtime classpath.
