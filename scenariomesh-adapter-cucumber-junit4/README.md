# scenariomesh-adapter-cucumber-junit4

Adapter for Cucumber suites executed through the JUnit 4 runner model.

## What it does

The adapter works from the compiled test runtime and JUnit runner/`Description` structure. It discovers executable Cucumber leaves through framework-native behavior rather than parsing `.feature` files directly.

This matters for repositories that use generated or handwritten Cucumber JUnit 4 runners: ScenarioMesh cares about the executable model produced by the framework, not which generator originally created a Java source file.

## Flow

```text
compiled target test runtime
        ↓
identify compatible Cucumber JUnit 4 runner(s)
        ↓
framework-native runner/Description discovery
        ↓
create lifecycle-safe ScenarioMesh work
        ↓
execute through the original runner semantics
```

If ScenarioMesh cannot uniquely preserve the runner selection/lifecycle semantics, ownership must be refused and Maven remains authoritative.
