# scenariomesh-adapter-junit-platform

Framework adapter for JUnit Platform based test runtimes.

## What it does

The adapter uses JUnit Platform's native launcher/test-plan model to determine executable identities and execute selected work. It preserves framework-native `UniqueId` identity instead of parsing Java source or guessing test names.

It is also the path used by engines that participate through JUnit Platform, including Cucumber's JUnit Platform engine when present and safely discoverable.

## Flow

```text
target test runtime classpath
        ↓
probe JUnit Platform availability
        ↓
native discovery through launcher/test plan
        ↓
map executable identities to ScenarioMesh tasks/scopes
        ↓
worker executes selected UniqueId(s) through JUnit Platform
```

## Safety boundary

A remote worker must advertise both the `junit-platform` adapter and the required engine for an engine-specific task. ScenarioMesh must not treat an adapter on one worker and the engine on another worker as sufficient coverage.

Unknown/custom engine semantics remain fail-closed when equivalent execution cannot be proven.
