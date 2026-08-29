# scenariomesh-adapter-testng

Adapter for compatible TestNG execution models.

## What it does

The adapter discovers and executes TestNG work using TestNG's runtime model while preserving method/test identity and terminal results.

The current product boundary is deliberately conservative: standard method-level `@Test` execution can be represented safely, while XML-suite-only, factory-heavy, or other advanced models must pass through when ScenarioMesh cannot prove equivalent selection and lifecycle behavior.

## Flow

```text
target TestNG runtime
        ↓
probe compatibility + native test model
        ↓
materialize executable method-level work
        ↓
coordinator schedules lifecycle-safe units
        ↓
worker executes through TestNG
```

Do not broaden support by simply enumerating annotations or class files. New TestNG behavior must first prove native Maven/TestNG semantic equivalence.
