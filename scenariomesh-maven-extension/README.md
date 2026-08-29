# scenariomesh-maven-extension

This is the transparent Maven integration layer behind the long-term `mvn test` user experience.

## Product rule

Loading the extension does **not** mean ScenarioMesh owns the test run.

```text
normal mvn test / mvn verify
        ↓
extension observes effective Maven lifecycle
        ↓
inspect participating Surefire/Failsafe execution
        ↓
resolve target test runtime + selection semantics
        ↓
can ScenarioMesh prove equivalent execution?
   ├── NO  → leave native Maven execution unchanged
   └── YES → prepare execution capability, then suppress only the duplicate native test execution
```

The extension must preserve compilation, source/resource generation, profiles, toolchains, system properties, and other Maven responsibilities that ScenarioMesh does not replace.

## Fail-closed boundary

Unknown execution-affecting Surefire/Failsafe configuration, unsupported framework models, ambiguous adapters, JPMS/runtime conditions ScenarioMesh cannot reproduce, or insufficient workers must result in pass-through rather than a guessed takeover.

This module is therefore the enforcement point for **PROVE COMPATIBILITY → TAKE OWNERSHIP**.
