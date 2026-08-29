# JUnit Platform + Cucumber discovery overlap fix — 2026-08-20

## Problem found by independent compatibility fixture

The untouched fixture passes natively with 100 executable Cucumber scenarios. After ScenarioMesh takeover, JUnit Platform exposed the same Cucumber executions through two valid discovery paths:

1. direct Cucumber engine discovery;
2. a JUnit Platform `@Suite` that delegates to the Cucumber engine.

The full JUnit UniqueIds differ because the suite path adds parent segments, so the old `Set<String> seen` check did not recognize the overlap. The result was 200 executions, 100 unique scenario identities, and 100 duplicates.

## Design goal

Do not special-case a particular runner class and do not blindly deduplicate by scenario name, feature name, or line number. Preserve legitimate execution contexts while removing only overlap that ScenarioMesh can prove represents the same Cucumber execution.

## Implemented design

`DiscoveredExecutionMerger` normalizes Cucumber descriptors to the UniqueId suffix beginning at `[engine:cucumber]`. This suffix is the Cucumber-owned execution identity within the JUnit Platform descriptor tree.

Rules:

- non-Cucumber tests retain their complete JUnit UniqueId;
- direct Cucumber + suite-owned Cucumber with the same Cucumber suffix: keep the suite-owned descriptor only;
- different Cucumber suffixes: keep both;
- two different explicit suites owning the same Cucumber suffix: keep both, because suite context may intentionally carry different selection/configuration semantics;
- exact duplicate full UniqueIds are collapsed;
- ScenarioMesh never deduplicates by display/scenario name.

This makes the merge conservative: remove only a provable direct-vs-suite duplicate, never silently erase ambiguous executions.

## Expected behavior

| Discovery input | Expected scheduled executions |
| --- | ---: |
| direct 100 + suite same 100 | 100 |
| suite 50 + direct different 50 | 100 |
| suite 50 + direct 25 overlapping + 25 different | 75 |
| Suite A 50 + Suite B same 50 | 100 (preserve both suite contexts) |
| ordinary JUnit 5 tests | unchanged |

## Regression coverage

`DiscoveredExecutionMergerTest` covers:

- direct-vs-suite overlap;
- different Cucumber executions;
- two explicit suites with the same scenario;
- ordinary JUnit 5 leaves;
- canonical Cucumber identity extraction.

## Compatibility validation

Required E2E acceptance after unit/build validation:

1. untouched small fixture native `mvn test` => 100 executions, 100 unique, 0 duplicates;
2. same fixture with ScenarioMesh => 100 executions, 100 unique, 0 duplicates;
3. untouched medium fixture native `mvn test` => 500 executions, 500 unique, 0 duplicates;
4. same fixture with ScenarioMesh => 500 executions, 500 unique, 0 duplicates.

If an execution identity is ambiguous in a future framework topology, the policy remains conservative: preserve it or pass through/fail compatibility explicitly rather than silently dropping a potentially legitimate test.

## Files changed

- `scenariomesh-adapter-junit-platform/src/main/java/io/scenariomesh/adapter/junitplatform/JUnitPlatformAdapter.java`
- `scenariomesh-adapter-junit-platform/src/main/java/io/scenariomesh/adapter/junitplatform/DiscoveredExecutionMerger.java`
- `scenariomesh-adapter-junit-platform/src/test/java/io/scenariomesh/adapter/junitplatform/DiscoveredExecutionMergerTest.java`
- `scenariomesh-adapter-junit-platform/pom.xml`

## Future extension

Keep framework-specific identity interpretation behind focused resolver/merger components rather than growing conditionals inside adapters. If another framework exposes equivalent executions through multiple descriptor trees, add a framework-specific identity strategy and reuse the same conservative merge principle.
