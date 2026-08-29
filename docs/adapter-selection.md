# Adapter Selection and Maven Test Artifacts

This document explains exactly how ScenarioMesh decides which framework adapter applies to a Maven test execution, what inputs it inspects, what Maven-generated Java source and compiled test classes are called, and why adapter selection is based on runtime evidence rather than filename guessing.

## 1. The short answer

ScenarioMesh does **not** choose an adapter by asking which plugin generated a particular file.

It waits until Maven has prepared the test runtime and then asks each registered adapter two questions:

```text
1. Is the framework/runtime needed by this adapter available?
2. If yes, can this adapter actually discover executable tests in the current Maven execution scope?
```

The adapter that can safely discover the executable work becomes the candidate owner.

## 2. What Maven prepares before ScenarioMesh discovery

A Maven build may contain original test source, generated test source, test resources, generated resources, and compiled test classes.

Typical terminology is:

```text
src/test/java/*.java
    -> test source code

target/generated-test-sources/**/*.java
    -> generated test sources

target/test-classes/**/*.class
    -> compiled test classes / test bytecode

src/test/resources/**
    -> test resources

target/test-classes/** copied resources
    -> processed test resources on the test runtime classpath
```

The broad term for what Maven has prepared by this point is the **compiled test runtime** or **test runtime classpath**.

The compiled test runtime contains the bytecode and resources that the framework will actually load when tests execute.

## 3. Source generation versus compilation

A build may perform:

```text
Original Java/Gherkin/resources
        |
        v
Maven/plugin generation
        |
        +--> generated .java test sources
        +--> generated feature/resource files
        |
        v
test compilation
        |
        +--> .class files in target/test-classes
        |
        v
runtime classpath assembled
        |
        v
Surefire/Failsafe/framework execution
```

ScenarioMesh does not need to know which generator originally created a test runner if the resulting compiled execution unit can be discovered safely through the framework.

## 4. Why ScenarioMesh does not select adapters from filenames

This would be unsafe:

```text
Found *.feature
 -> choose Cucumber

Found *Test.class
 -> choose JUnit

Found *IT.class
 -> choose Failsafe/Cucumber
```

Those are conventions, not reliable framework ownership rules.

A repository may contain Cucumber libraries but run JUnit tests. It may contain TestNG transitively. It may use custom test names. Surefire and Failsafe include/exclude rules may select only part of the compiled classes.

Therefore ScenarioMesh uses runtime/framework evidence.

## 5. Inputs to adapter selection

After Maven has prepared the execution scope, ScenarioMesh builds a discovery context containing information such as:

```text
compiled test roots
runtime classpath
Surefire/Failsafe include filters
Surefire/Failsafe exclude filters
system properties
configured adapter intent
adapter mismatch policy
```

The current discovery entry point passes test roots and class-selection regexes into the adapter context.

## 6. Registered adapter registry

The current runtime contains a registry conceptually like:

```text
AdapterRegistry
  |
  +-- junit-platform
  +-- cucumber-junit4
  +-- testng
```

ScenarioMesh loops through the registry rather than hard-coding framework selection in the coordinator.

## 7. Phase A - availability probe

Each adapter first answers:

```text
isAvailable(classLoader)?
```

This asks whether the framework runtime required by that adapter exists in the target project's test runtime classpath.

Examples:

```text
JUnit Platform adapter
 -> are required JUnit Platform launcher/runtime classes available?

Cucumber JUnit 4 adapter
 -> are the required JUnit 4/Cucumber runner classes available?

TestNG adapter
 -> are required TestNG runtime classes available?
```

Availability is only a first filter. It does **not** mean the adapter owns tests.

A repository can legitimately have all three frameworks present on the classpath.

## 8. Phase B - native discovery probe

For every available adapter, ScenarioMesh calls that adapter's native discovery logic.

Conceptually:

```text
adapter available?
    |
    NO -> record available=false
    |
    YES
    v
adapter.discover(context)
    |
    v
0 or more ScenarioTask objects
```

The number of discovered executable tasks becomes adapter evidence.

Example:

```text
junit-platform
available=true
found=0

cucumber-junit4
available=true
found=25

testng
available=true
found=0
```

Result:

```text
candidate owner = cucumber-junit4
```

## 9. JUnit Platform probe

The JUnit Platform adapter does not parse `.java` files.

It uses the JUnit Platform discovery mechanism:

```text
eligible compiled test scope
        |
        v
LauncherDiscoveryRequest
        |
        v
JUnit Platform Launcher
        |
        v
installed TestEngines
        |
        +--> Jupiter Engine
        +--> Cucumber Engine
        +--> other compatible engines
        |
        v
TestPlan
        |
        v
native executable test identifiers
        |
        v
ScenarioTask
```

If the Platform finds no executable tests in the selected execution scope, that adapter contributes zero tasks.

## 10. Cucumber JUnit 4 probe

The Cucumber JUnit 4 adapter works differently because legacy JUnit 4 does not use JUnit Platform discovery in the same way.

Conceptually:

```text
eligible compiled test classes
        |
        v
identify supported Cucumber JUnit 4 runners
        |
        v
ask JUnit/Cucumber runner for Description tree
        |
        v
walk executable leaves
        |
        v
ScenarioTask
```

The adapter does not care whether a runner was handwritten or generated by a build plugin. It cares whether the compiled class represents a supported Cucumber JUnit 4 execution unit.

## 11. TestNG probe

The TestNG adapter uses the TestNG runtime model rather than parsing source text.

Conceptually:

```text
eligible compiled test classes
        |
        v
TestNG runtime discovery
        |
        v
supported classes/methods/invocations
        |
        v
ScenarioTask
```

A TestNG DataProvider invocation may need a stronger native identity than a simple method name. That remains adapter responsibility.

## 12. Auto mode selection

With:

```yaml
execution:
  adapter: auto
```

ScenarioMesh evaluates every registered adapter.

Current decision model:

```text
probe all adapters
        |
        v
collect evidence
        |
        +--> exactly one adapter discovers >0 tests
        |        |
        |        v
        |      select it
        |
        +--> no adapter discovers tests
        |        |
        |        v
        |      fail clearly / no ownership
        |
        +--> more than one discovers tests
                 |
                 v
              ambiguous
              do not guess
```

If an available adapter throws a discovery error, auto mode does not silently ignore that failure and choose another adapter as if nothing happened. The error is included in the adapter evidence because an incomplete probe could hide ownership ambiguity.

## 13. Explicit adapter mode

A repository can state intent:

```yaml
execution:
  adapter: cucumber-junit4
```

ScenarioMesh still validates that adapter against runtime evidence.

If it discovers executable tests, the configured adapter is used.

If it does not, ScenarioMesh applies `adapterMismatchPolicy` rather than blindly executing through the wrong framework.

This separates:

```text
user intent
```

from:

```text
runtime evidence
```

## 14. Why generator origin is usually irrelevant

Consider two repositories.

Repository A:

```text
src/test/java/RunRegression.java
 -> compiled to target/test-classes/RunRegression.class
```

Repository B:

```text
build plugin generates target/generated-test-sources/RunRegression.java
 -> Maven test-compile
 -> target/test-classes/RunRegression.class
```

If both resulting classes expose the same supported native framework execution model, ScenarioMesh can treat them through the same adapter.

The source-generation history is not normally required for runtime execution.

What matters is:

```text
What does Maven select?
What compiled tests/resources exist now?
Which framework can natively discover them?
Can each executable unit be uniquely identified and safely executed?
```

## 15. When origin can matter indirectly

ScenarioMesh may care about generated artifacts indirectly when they affect execution semantics, for example:

```text
generated source directory is missing from test compilation
Maven execution filters select only generated runners
generated resources are needed on the runtime classpath
the generator creates one execution unit per data row
```

But even then ScenarioMesh should reason from the effective Maven/runtime state rather than hard-code knowledge of one generator plugin.

## 16. Simple full example

Suppose Maven produces:

```text
target/test-classes/
  LoginTest.class
  GeneratedRegressionRunner.class
```

and runtime dependencies contain JUnit Platform, JUnit 4, Cucumber, TestNG, and Selenium.

ScenarioMesh performs:

```text
JUnit Platform adapter
  available? YES
  native discover -> 0 selected executable tests

Cucumber JUnit4 adapter
  available? YES
  native discover -> 12 executable leaves

TestNG adapter
  available? YES
  native discover -> 0 executable tests
```

Therefore:

```text
Selected adapter = cucumber-junit4
Discovered tasks = 12
```

It does not matter whether `GeneratedRegressionRunner.java` came from source control or a Maven generator.

## 17. Why this design is generic

The coordinator never contains logic such as:

```text
if file ends with _IT -> Cucumber
if .feature exists -> Cucumber
if @Test text exists -> JUnit
```

Instead:

```text
Maven execution scope
        |
        v
AdapterRegistry
        |
        v
availability evidence
        |
        v
native framework discovery evidence
        |
        v
safe adapter ownership
```

This allows different repositories to use different source layouts, generated test conventions, runner names, and framework versions while preserving one ScenarioMesh core model.

## 18. Terminology cheat sheet

| Term | Meaning |
| --- | --- |
| Test source | Java test source such as `src/test/java/.../*.java` |
| Generated test source | Java source created during the Maven build, commonly under `target/generated-test-sources` |
| Test compilation | Maven phase/process that compiles test `.java` into JVM `.class` bytecode |
| Compiled test class | A `.class` file produced from test source/generated test source |
| Test bytecode | Another term for compiled test `.class` output |
| Test resources | Non-Java runtime files such as feature/config/resource files |
| Test classes directory | Commonly `target/test-classes` |
| Test runtime classpath | The complete runtime view: compiled main/test classes, processed resources, and resolved test dependencies |
| Framework adapter | ScenarioMesh bridge to a framework's native discovery/execution model |
| Adapter availability | Required framework runtime exists on the test classpath |
| Adapter evidence | Availability + discovery result/error used to decide ownership |
| Native discovery | Asking JUnit/Cucumber/TestNG itself what is executable instead of parsing files |

## 19. Short explanation for another engineer

> Maven first prepares the compiled test runtime: original/generated test sources are compiled into test bytecode and resources/dependencies are placed on the test runtime classpath. ScenarioMesh does not choose an adapter by filename or by asking which plugin generated a runner. It probes every registered adapter against that runtime. Each adapter first checks whether its framework is available, then uses the framework's native discovery API/model to see whether it can discover executable tests within the Maven-selected scope. ScenarioMesh selects the uniquely applicable adapter, or refuses to guess when ownership is ambiguous.