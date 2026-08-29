# ScenarioMesh — Complete End-to-End Execution Flow

This is the **single authoritative document** for understanding how ScenarioMesh works from the moment a developer types `mvn test` until Maven receives the final result.

The most important rule is:

```text
PROVE COMPATIBILITY  ──►  TAKE OWNERSHIP
CANNOT PROVE         ──►  PASS THROUGH TO NATIVE MAVEN UNCHANGED
```

ScenarioMesh is not intended to force itself into every Java test repository. It first studies the target Maven execution and proves that it can preserve the target repository's test-selection and lifecycle semantics. Only after that proof may it suppress the native duplicate execution and own the run.

---

# 1. One real target repository used through this whole explanation

We will use the repository fixture that exists in ScenarioMesh today:

```text
examples/cucumber-junit-platform-example/
├── .mvn/
│   └── extensions.xml
├── pom.xml
└── src/test/
    ├── java/
    │   └── ... Cucumber step-definition code
    └── resources/
        ├── features/
        │   └── smoke.feature
        └── junit-platform.properties
```

Its relevant Maven model is currently:

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
</properties>

<dependencies>
  <dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-java</artifactId>
    <version>7.20.1</version>
    <scope>test</scope>
  </dependency>

  <dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-junit-platform-engine</artifactId>
    <version>7.20.1</version>
    <scope>test</scope>
  </dependency>

  <dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-suite-engine</artifactId>
    <version>1.11.4</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

ScenarioMesh is loaded into this target through:

```xml
<!-- .mvn/extensions.xml -->
<extensions>
  <extension>
    <groupId>io.scenariomesh</groupId>
    <artifactId>scenariomesh-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Its JUnit Platform configuration currently contains:

```properties
cucumber.glue=example.steps
```

And the actual `smoke.feature` currently contains **one physical feature file and three scenarios**:

```gherkin
Feature: cucumber junit platform takeover

  Scenario: first cucumber scenario
    Given cucumber scenario first passes

  Scenario: second cucumber scenario
    Given cucumber scenario second passes

  Scenario: third cucumber scenario
    Given cucumber scenario third passes
```

So throughout the main flow, remember our concrete target:

```text
TARGET EXAMPLE
-------------
Maven project                  = cucumber-junit-platform-example
Requested command              = mvn test
Configured Java release        = 17
Framework                      = Cucumber
Integration                    = Cucumber JUnit Platform Engine
Cucumber version               = 7.20.1
JUnit Platform Suite Engine    = 1.11.4
Feature source files           = 1
Scenarios in smoke.feature     = 3
Glue                           = example.steps
```

A different repository might instead contain JUnit Jupiter, JUnit Vintage/JUnit 4, TestNG, Cucumber JUnit 4, multiple modules, Surefire/Failsafe configuration, Maven Toolchains, JPMS modules, tags/groups, includes/excludes, custom JUnit engines, or a mixture of these. ScenarioMesh cannot assume that every repository looks like this fixture.

---

# 2. Entire flow at a glance

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. USER RUNS THE TARGET REPOSITORY'S NORMAL COMMAND                         │
│                         mvn test                                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. SCENARIOMESH MAVEN EXTENSION IS PRESENT AND OBSERVES THE MAVEN SESSION   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. INSPECT THE REAL MAVEN EXECUTION                                         │
│ goals • module • Surefire/Failsafe • filters • properties • lifecycle       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. RESOLVE THE REAL TEST RUNTIME                                            │
│ test JVM • toolchain • classpath/module path • framework/engine/provider    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. PROVE SCENARIOMESH HAS A SAFE ADAPTER AND CAN PRESERVE SELECTION         │
└─────────────────────────────────────────────────────────────────────────────┘
                         │ YES                           │ NO / UNKNOWN
                         ▼                               ▼
┌──────────────────────────────────────────┐  ┌───────────────────────────────┐
│ 6A. TAKE OWNERSHIP                      │  │ 6B. PASS THROUGH              │
│ Suppress native duplicate execution     │  │ Leave native Maven execution │
└──────────────────────────────────────────┘  │ completely authoritative      │
                         │                   └───────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 7. DISCOVER THE EXECUTABLE TEST/SCENARIO IDENTITIES                        │
│ Cucumber: 1 feature source → 3 executable scenarios in our example          │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 8. BUILD LIFECYCLE-SAFE WORK UNITS                                          │
│ Do not destroy suite/class/Cucumber/JUnit lifecycle semantics               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 9. PREPARE WORKERS AND PROVE EACH WORKER'S CAPABILITY                       │
│ Java • adapter • engine • protocol • auth • runtime • local/remote           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 10. SCHEDULER ASSIGNS ONLY ELIGIBLE WORK TO EACH WORKER                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 11. WORKER EXECUTES THE ORIGINAL TARGET TEST THROUGH ITS NATIVE ENGINE      │
│ ScenarioMesh schedules; Cucumber/JUnit/TestNG still execute test semantics  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 12. LEASES + HEARTBEATS + CRASH/UNCERTAINTY PROTECTION                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 13. COLLECT RESULTS + EVENTS + REPORTS                                      │
│ passed/failed/skipped • JUnit/Surefire-compatible reporting                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 14. RETURN ONE FINAL RESULT TO MAVEN                                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

The following sections walk through exactly what each box means.

---

# 3. BOX 1 — The user still runs the target repository normally

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 1 — USER COMMAND                                                        │
│                                                                             │
│ Current example:                                                            │
│     cd examples/cucumber-junit-platform-example                             │
│     mvn test                                                                │
│                                                                             │
│ The desired product experience is NOT:                                      │
│     manually split features                                                 │
│     create five special runner files                                        │
│     run a custom shell sharding script                                      │
│                                                                             │
│ The normal Maven command remains the entry point.                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

ScenarioMesh must understand the **actual Maven request**, not merely scan the repository and execute everything it finds.

For our target, the request is `test`. Other real requests might involve `verify`, Failsafe integration tests, a reactor build, `-Dtest=...`, Cucumber tag filters, TestNG groups, Surefire includes/excludes, profiles, system properties, or module-specific invocation.

That distinction matters. If the developer asks Maven to run only one selected test, ScenarioMesh must not discover the whole repository and run everything.

---

# 4. BOX 2 — Maven loads ScenarioMesh

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 2 — MAVEN INTEGRATION                                                   │
│                                                                             │
│ Current example has:                                                        │
│   .mvn/extensions.xml                                                       │
│       └─ io.scenariomesh:scenariomesh-maven-extension:0.1.0-SNAPSHOT        │
│                                                                             │
│ ScenarioMesh can now observe the Maven session early enough to inspect      │
│ the requested build before native test execution.                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

At this point ScenarioMesh has **not earned ownership** merely because its extension is loaded.

Loading means only: "ScenarioMesh is available to evaluate this build."

If ScenarioMesh is disabled, the request is outside a supported takeover path, or compatibility cannot be established, the correct behavior is native Maven pass-through.

```text
EXTENSION LOADED ≠ SCENARIOMESH OWNS THE TEST RUN
```

---

# 5. BOX 3 — Inspect what Maven actually intends to execute

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 3 — MAVEN EXECUTION INSPECTION                                          │
│                                                                             │
│ Current example:                                                            │
│   goal/lifecycle request = test                                              │
│   module                 = cucumber-junit-platform-example                   │
│                                                                             │
│ ScenarioMesh must determine the effective test execution that Maven would   │
│ otherwise perform.                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

Examples of values that may matter in another target repository include:

- Surefire versus Failsafe execution;
- active Maven profiles;
- reactor/module boundaries;
- `skipTests`, `maven.test.skip`, or equivalent skip settings;
- `-Dtest=ClassName`, method selection, includes, excludes;
- TestNG groups;
- JUnit tags;
- Cucumber tag/name filters;
- provider/engine configuration;
- system properties supplied to the test JVM;
- Surefire/Failsafe `argLine` and JVM-related settings;
- plugin configuration inherited from parent POMs;
- configuration added by active profiles.

The important point is that ScenarioMesh reasons from the **effective Maven project/execution**, not from a simplistic assumption such as "there is a `src/test` directory, therefore run all tests."

### Pass-through here

If ScenarioMesh cannot faithfully understand which Maven test execution it would be replacing, it must not take ownership.

```text
Cannot prove the intended test selection
                 │
                 ▼
        NATIVE MAVEN PASS-THROUGH
```

---

# 6. BOX 4 — Resolve the test JVM and test runtime

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 4 — RUNTIME RESOLUTION                                                  │
│                                                                             │
│ Current example declares:                                                   │
│   maven.compiler.release = 17                                               │
│                                                                             │
│ Current framework dependencies include:                                     │
│   cucumber-java                    7.20.1                                   │
│   cucumber-junit-platform-engine   7.20.1                                   │
│   junit-platform-suite-engine      1.11.4                                   │
│                                                                             │
│ Current Cucumber glue:                                                       │
│   example.steps                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

`maven.compiler.release=17` is useful information, but it is **not by itself sufficient** to choose the worker JVM. A target repository can use Maven Toolchains, plugin `jdkToolchain` configuration, another installed JDK, module-path execution, custom JVM options, or other runtime constraints.

ScenarioMesh therefore needs to preserve the real test runtime rather than blindly launching workers with ScenarioMesh's own `java.home`.

A different target may be:

```text
Target A: Java 17 + JUnit Jupiter
Target B: Java 21 + TestNG
Target C: Java 17 + Cucumber JUnit Platform
Target D: Maven Toolchains selects Java 21 although Maven itself runs on Java 17
Target E: JPMS/module-path tests
Target F: custom JUnit Platform engine
```

### Pass-through here

If ScenarioMesh cannot safely reproduce a target's runtime/classpath/module-path requirements, ownership is unsafe. Native Maven remains authoritative.

---

# 7. BOX 5 — Identify the framework/engine and choose an adapter

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 5 — ADAPTER / ENGINE PROOF                                              │
│                                                                             │
│ Current example resolves to:                                                │
│   Cucumber                                                                  │
│        ↓                                                                    │
│   cucumber-junit-platform-engine                                            │
│        ↓                                                                    │
│   JUnit Platform                                                            │
│                                                                             │
│ ScenarioMesh requires an adapter that understands how to discover and       │
│ execute this integration without changing its semantics.                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

ScenarioMesh's adapter model exists because these are not identical execution models:

```text
JUnit Platform / Jupiter     ≠ TestNG
TestNG                       ≠ Cucumber JUnit Platform
Cucumber JUnit Platform      ≠ old Cucumber JUnit 4 runner
known engine                 ≠ arbitrary custom JUnit engine
```

For another project, ScenarioMesh might find:

- `junit-jupiter`;
- JUnit Vintage;
- Cucumber JUnit Platform Engine;
- TestNG;
- a supported mixed configuration;
- an unknown third-party engine.

Unknown is not the same as compatible.

### Pass-through here

If an engine/provider is unknown or ScenarioMesh lacks a contract proving how to execute it correctly, the safe result is pass-through, not guessing.

---

# 8. BOX 6 — The ownership decision

This is the most important boundary in the architecture.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 6 — FINAL PRE-OWNERSHIP QUESTION                                        │
│                                                                             │
│ Can ScenarioMesh prove that it understands:                                 │
│                                                                             │
│   ✓ requested Maven execution                                               │
│   ✓ selected module(s)                                                      │
│   ✓ test selection/filter semantics                                         │
│   ✓ correct test JVM/runtime                                                │
│   ✓ framework/provider/engine                                               │
│   ✓ supported adapter                                                       │
│   ✓ lifecycle/scoping requirements                                          │
│   ✓ enough execution capacity/capability for the planned run                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                         │                              │
                       YES                              NO
                         │                              │
                         ▼                              ▼
┌──────────────────────────────────────┐    ┌──────────────────────────────────┐
│ SCENARIOMESH TAKES OWNERSHIP         │    │ NATIVE MAVEN PASS-THROUGH        │
│                                      │    │                                  │
│ Native duplicate test execution is  │    │ ScenarioMesh does NOT suppress   │
│ suppressed for the owned execution. │    │ the original Maven execution.    │
└──────────────────────────────────────┘    └──────────────────────────────────┘
```

For our current fixture, the intended path is the left side because the Cucumber/JUnit Platform runtime is a supported takeover path.

A key safety property is:

```text
PASS-THROUGH HAPPENS BEFORE OWNERSHIP.
```

Once ScenarioMesh has suppressed native execution and work may have started, a later worker crash must **not** simply cause Maven to run all tests natively from the beginning. Doing so could duplicate database writes, browser actions, messages, payments, fixture creation, or other test side effects.

So there are two very different ideas:

```text
Before ownership: "I cannot prove compatibility" → safely pass through.
After ownership:  "Execution failed"              → report/manage the failure safely.
```

---

# 9. BOX 7 — Native Maven is suppressed only after ownership

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 7 — PREVENT DUPLICATE EXECUTION                                         │
│                                                                             │
│ Without protection:                                                         │
│                                                                             │
│      ScenarioMesh runs scenario 1,2,3                                       │
│                  +                                                          │
│      Surefire also runs scenario 1,2,3                                      │
│                  =                                                          │
│      DUPLICATE TEST EXECUTION                                                │
│                                                                             │
│ Therefore native execution is suppressed only for an execution that         │
│ ScenarioMesh has already proven it can own.                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

This is why ScenarioMesh is not merely another thread pool placed next to Surefire. It has an explicit ownership model.

---

# 10. BOX 8 — Discover executable identities

Now ScenarioMesh asks the supported framework integration what the **actual executable tests** are.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 8 — DISCOVERY                                                           │
│                                                                             │
│ Physical source in our current target:                                      │
│                                                                             │
│   src/test/resources/features/smoke.feature                                 │
│                         │                                                   │
│                         ▼                                                   │
│   Feature: cucumber junit platform takeover                                 │
│                         │                                                   │
│              ┌──────────┼──────────┐                                        │
│              ▼          ▼          ▼                                        │
│          Scenario 1 Scenario 2 Scenario 3                                   │
│              │          │          │                                        │
│              └──────────┼──────────┘                                        │
│                         ▼                                                   │
│       3 executable Cucumber test identities                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

ScenarioMesh should preserve framework-native identity information such as JUnit Platform `UniqueId` where appropriate rather than inventing ambiguous names.

The source file is not itself necessarily the final scheduling unit. Discovery can expose finer executable identities while lifecycle rules may later require some of them to remain grouped.

---

# 11. Important correction — 5 Cucumber Examples rows do NOT mean 5 `.feature` files

Suppose we change our one `smoke.feature` to this:

```gherkin
Feature: payment

  Scenario Outline: payment works with <method>
    Given payment method <method> passes

    Examples:
      | method     |
      | Visa       |
      | Mastercard |
      | Amex       |
      | PayPal     |
      | Debit      |
```

What exists on disk?

```text
Physical .feature files = 1
Scenario Outline        = 1
Examples rows           = 5
Executable test cases   = 5
```

The conceptual flow is:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ smoke.feature — ONE PHYSICAL FILE                                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ CUCUMBER PARSES GHERKIN                                                     │
│ Scenario Outline + five Examples rows                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ FIVE EXECUTABLE CUCUMBER CASES                                              │
│                                                                             │
│ payment works with Visa                                                     │
│ payment works with Mastercard                                               │
│ payment works with Amex                                                     │
│ payment works with PayPal                                                   │
│ payment works with Debit                                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ JUNIT PLATFORM / CUCUMBER ENGINE EXPOSES EXECUTABLE DESCRIPTORS             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ SCENARIOMESH CAN SCHEDULE THE EXECUTABLE IDENTITIES WHEN LIFECYCLE-SAFE     │
└─────────────────────────────────────────────────────────────────────────────┘
```

Cucumber does **not normally generate five new physical `.feature` files** for those five rows.

Some third-party build tools or older parallelization approaches generate multiple **Java runner classes** to shard Cucumber work. That is a different mechanism. Generated Java runners are not the same thing as Cucumber turning one source feature into five feature files.

---

# 12. BOX 9 — Convert discovered tests into lifecycle-safe work units

Finding three scenarios does not automatically mean ScenarioMesh should treat every discovered node as independently retryable and context-free.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 9 — LIFECYCLE / SCOPE ANALYSIS                                          │
│                                                                             │
│ Current example:                                                            │
│   3 Cucumber scenarios                                                       │
│                                                                             │
│ ScenarioMesh asks:                                                          │
│   What can be distributed independently?                                    │
│   What setup/teardown belongs to class/suite/run scope?                     │
│   Is there global Cucumber lifecycle state?                                 │
│   Is JUnit PER_CLASS involved?                                              │
│   Would retrying a partial scope duplicate side effects?                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

Examples of lifecycle constructs ScenarioMesh must respect include:

- JUnit `@BeforeAll` / `@AfterAll`;
- JUnit `PER_CLASS` test instance lifecycle;
- suite-level behavior;
- Cucumber global lifecycle/hooks;
- TestNG suite/class/method configuration;
- parameterized invocations that need stable identity;
- framework-specific container/test relationships.

ScenarioMesh's goal is **parallelism without silently changing semantics**.

If two test cases cannot safely be separated, the correct work unit may contain a larger lifecycle scope even though discovery found multiple individual test descriptors.

---

# 13. BOX 10 — Prepare isolated workers

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 10 — WORKER PREPARATION                                                 │
│                                                                             │
│ Coordinator has work units from our Cucumber target.                        │
│                                                                             │
│ It prepares execution lanes:                                                │
│                                                                             │
│       Worker JVM 1     Worker JVM 2     Worker JVM 3     Worker JVM 4       │
│                                                                             │
│ A worker process is currently treated as one execution lane.                │
└─────────────────────────────────────────────────────────────────────────────┘
```

Workers can be local processes or, in distributed mode, workers supplied by remote/CI agents.

The worker must use the target test runtime, not an arbitrary JVM chosen because ScenarioMesh itself happened to start under it.

In a Jenkins environment, Jenkins still owns machines/nodes/executors. ScenarioMesh schedules test work **inside the capacity Jenkins has provided**; it does not replace Jenkins infrastructure scheduling.

---

# 14. BOX 11 — Worker capability proof

Not every worker is necessarily able to run every task.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 11 — CAPABILITY-AWARE WORKER VALIDATION                                 │
│                                                                             │
│ Our task requires approximately:                                            │
│   adapter/engine = Cucumber via JUnit Platform                              │
│   compatible target Java runtime                                            │
│                                                                             │
│ A remote worker can also advertise:                                         │
│   supported adapters                                                        │
│   supported JUnit engines                                                   │
│   Java/runtime facts                                                        │
│   worker/slot identity                                                      │
│   protocol support                                                          │
│                                                                             │
│ A task is sent only to a worker that can execute that exact requirement.    │
└─────────────────────────────────────────────────────────────────────────────┘
```

Example heterogeneous fleet:

```text
Worker A: junit-platform + junit-jupiter
Worker B: testng
Worker C: junit-platform + cucumber engine
```

A Cucumber/JUnit Platform task must not be assigned to Worker B merely because Worker B is idle.

Likewise, it is not enough for one worker to advertise `junit-platform` while some *different* worker advertises the required engine. The same chosen worker needs the complete capability combination required by the task.

---

# 15. BOX 12 — Distributed protocol/security check

For remote workers, scheduling also crosses a protocol boundary.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 12 — DISTRIBUTED SESSION                                                │
│                                                                             │
│ Worker HELLO                                                                │
│      ↓                                                                      │
│ authentication + capability registration                                    │
│      ↓                                                                      │
│ protocol negotiation                                                        │
│      ↓                                                                      │
│ established worker session                                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

Current ScenarioMesh protocol evolution includes a bootstrap/negotiation mechanism so compatible protocol generations can establish the highest mutually supported session version rather than assuming every worker is identical.

Security principles include:

- application registration token;
- TLS for non-loopback remote transport;
- mTLS support;
- secrets are not intentionally exposed as process command-line arguments;
- registration proves identity/capability, but a registration message itself does not grant authority over arbitrary work.

An incompatible/untrusted worker is not "almost good enough". It must not receive owned work.

---

# 16. BOX 13 — Scheduling

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 13 — SCHEDULER                                                          │
│                                                                             │
│ Ready work:                                                                 │
│   Scenario 1                                                               │
│   Scenario 2                                                               │
│   Scenario 3                                                               │
│                                                                             │
│ Eligible workers:                                                          │
│   W1   W2   W3 ...                                                         │
│                                                                             │
│ Scheduler chooses from READY + COMPATIBLE combinations.                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

ScenarioMesh can use scheduling information such as prior execution duration when configured, while a strict FIFO mode can preserve first-in-first-out ordering. Capability eligibility still comes before "which idle worker would be fastest?"

Conceptually:

```text
Is worker capable of this task?
          │
          ├── NO ──► do not assign
          │
          └── YES
               │
               ▼
       scheduler may consider it
```

This is one of the major differences from simply asking Surefire to create N local JVM forks.

---

# 17. BOX 14 — What the worker actually executes

ScenarioMesh does not reimplement Cucumber's step engine or JUnit/TestNG assertions.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 14 — EXECUTION                                                          │
│                                                                             │
│ ScenarioMesh coordinator:                                                   │
│     "Worker 2, execute this exact discovered Cucumber test identity."       │
│                                                                             │
│ Worker 2:                                                                   │
│     recreates target runtime/classpath                                      │
│     invokes the supported framework adapter                                 │
│     adapter invokes Cucumber/JUnit Platform                                 │
│     Cucumber matches steps using glue = example.steps                       │
│     original target test code executes                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

So the responsibilities are separated:

```text
ScenarioMesh        = ownership + orchestration + worker isolation + scheduling
JUnit/Cucumber/etc. = framework discovery/execution semantics
Target tests        = actual assertions, hooks, browser/API/database behavior
```

---

# 18. BOX 15 — Leases, heartbeats, stale results and uncertain execution

Distributed parallelism needs more than a queue.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 15 — WORK AUTHORITY                                                     │
│                                                                             │
│ Coordinator assigns:                                                        │
│   workUnitId                                                                │
│   leaseId                                                                   │
│   lease expiry                                                              │
│                                                                             │
│ Worker heartbeat must match the authoritative lease.                        │
│ Result must also belong to the authoritative lease.                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

Why?

Imagine Worker A receives Scenario 2, loses its network connection, and the coordinator later replaces that lease. A very late result from the old execution must not overwrite the authoritative state as though nothing happened.

ScenarioMesh therefore distinguishes liveness from work authority. An idle presence heartbeat can indicate that a worker/socket is alive, but it must not magically create or renew a work lease.

Also, lifecycle-scoped work cannot always be blindly retried. If setup or a scenario partially executed before a crash, replaying it may duplicate side effects. "We lost the worker" does not prove "nothing executed."

---

# 19. BOX 16 — Results and reports

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 16 — RESULT AGGREGATION                                                 │
│                                                                             │
│ Worker results                                                              │
│      │                                                                      │
│      ├─ passed                                                              │
│      ├─ failed                                                              │
│      ├─ skipped                                                             │
│      └─ execution/error metadata                                            │
│                                                                             │
│ Coordinator validates and aggregates them.                                  │
│                                                                             │
│ ScenarioMesh emits structured events and Maven/JUnit/Surefire-compatible    │
│ reporting outputs where supported.                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

For our current three-scenario fixture, successful execution should represent those same three original Cucumber scenarios—not three ScenarioMesh-created copies and not three extra native Maven executions.

The reporting layer should preserve stable test identity and make external CI/report consumers usable without requiring them to understand ScenarioMesh's internal scheduler.

---

# 20. BOX 17 — Maven receives one final outcome

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ BOX 17 — COMPLETION                                                         │
│                                                                             │
│ ScenarioMesh-owned run succeeded ──► Maven build gets success               │
│ ScenarioMesh-owned run failed    ──► Maven build gets failure               │
│                                                                             │
│ Native pass-through path          ──► Native Maven provider decides outcome │
└─────────────────────────────────────────────────────────────────────────────┘
```

From a repository user's perspective, the command remains Maven-shaped. ScenarioMesh changes the execution runtime behind that command only when it can prove that doing so is safe.

---

# 21. Where exactly can ScenarioMesh pass through?

The easiest way to remember pass-through is:

```text
BEFORE OWNERSHIP, UNCERTAINTY IS A REASON NOT TO TAKE OVER.
AFTER OWNERSHIP, UNCERTAINTY IS AN EXECUTION PROBLEM TO HANDLE/REPORT SAFELY.
```

Examples of pre-ownership reasons to leave Maven alone include:

| Check | Our example now | Another target might have | Safe behavior if ScenarioMesh cannot prove it |
|---|---|---|---|
| Maven request | `mvn test` | `verify`, special executions, reactor selection | Pass through |
| Test runtime | Java release 17 | toolchain-selected JDK, JPMS/module path | Pass through if runtime cannot be reproduced |
| Framework | Cucumber | TestNG, Jupiter, JUnit 4 | Use supported adapter or pass through |
| JUnit engine | Cucumber JUnit Platform Engine | Jupiter, Vintage, custom engine | Unknown engine → pass through |
| Test filters | no special filter in basic example | `-Dtest`, tags, groups, includes/excludes | If exact selection cannot be preserved → pass through |
| Lifecycle | basic fixture scenarios | PER_CLASS, suite/global lifecycle | If safe scope cannot be modeled → pass through |
| Worker capability | compatible worker expected | heterogeneous remote fleet | Do not assign work to incapable workers |
| Remote protocol/security | compatible registered session | incompatible protocol/token/TLS | Reject worker / do not grant work authority |

Pass-through is a feature, not a failure. It protects the target repository from an unsafe takeover.

---

# 22. ScenarioMesh vs Maven Surefire `forkCount`

Yes: Maven Surefire already supports forked JVM execution. That is useful and important. ScenarioMesh is solving a broader orchestration problem.

A simplified Surefire picture is:

```text
                        MAVEN / SUREFIRE OWNS EXECUTION
                                      │
                       forkCount = 4  │
                                      ▼
                   ┌────────┬────────┬────────┬────────┐
                   │ JVM 1  │ JVM 2  │ JVM 3  │ JVM 4  │
                   └────────┴────────┴────────┴────────┘
                         provider/framework executes tests
```

`forkCount` primarily controls how many forked test JVMs Surefire can use. `reuseForks` controls whether those forked JVMs can be reused. Framework/provider settings determine what parallel work can happen inside/across those executions.

ScenarioMesh's conceptual picture is:

```text
                            MAVEN COMMAND
                                 │
                                 ▼
                   SCENARIOMESH COMPATIBILITY PROOF
                        │                     │
                       NO                    YES
                        │                     │
                        ▼                     ▼
                  native Surefire      ScenarioMesh owns run
                                              │
                                    discover executable identities
                                              │
                                    lifecycle-safe work units
                                              │
                                  capability-aware scheduler
                                      │       │       │
                                      ▼       ▼       ▼
                                  Worker 1 Worker 2 Worker 3 ...
                                  local and/or remote capacity
```

The key distinction is not merely "ScenarioMesh has four JVMs too."

It is the ownership/discovery/scheduling/capability/lifecycle/distributed layer around those JVMs.

---

# 23. ScenarioMesh vs framework/runner parallelism

A JUnit, TestNG, or Cucumber runner/engine can itself support parallel execution. That is again useful, but its responsibility is different.

## Framework/runner parallelism

Typical shape:

```text
Maven/Surefire
     │
     ▼
JUnit/TestNG/Cucumber runner or engine
     │
     ├── test/scenario A
     ├── test/scenario B
     └── test/scenario C
```

The runner/engine generally understands its own test model extremely well. It is the authority for things like Cucumber parsing, hooks, TestNG configuration, JUnit descriptors, assertions, and framework lifecycle.

## ScenarioMesh

```text
Maven
  │
  ▼
ScenarioMesh ownership decision
  │
  ▼
Framework-native discovery through adapter
  │
  ▼
Executable identities + lifecycle-safe work units
  │
  ▼
Cross-worker scheduler / worker lifecycle
  │
  ▼
Worker invokes the real framework engine
```

ScenarioMesh does **not** need to replace Cucumber's understanding of Gherkin. It uses the framework/runtime to preserve that semantic model, then adds orchestration around it.

---

# 24. Is a Cucumber runner basically "running a feature scenario"?

Not exactly.

A runner/engine is the integration entry point that tells the testing platform how to discover and execute Cucumber tests. Depending on the integration, it may expose many features/scenarios/example rows, not simply one runner = one scenario.

For our current JUnit Platform example:

```text
Cucumber JUnit Platform Engine
            │
            ▼
finds Cucumber feature/scenario test descriptors
            │
            ▼
JUnit Platform can identify those executable nodes
```

ScenarioMesh then works with those executable identities where its adapter contract says it is safe.

Older/generated-runner approaches can look different:

```text
Feature files
    │
runner generator
    │
    ├── GeneratedRunner1.java
    ├── GeneratedRunner2.java
    ├── GeneratedRunner3.java
    └── ...
```

That is **generated runner sharding**. It can be a way to make Surefire see multiple Java test classes and distribute them, but it is not the same architecture as ScenarioMesh and it is not Cucumber creating more `.feature` files.

---

# 25. Direct comparison

| Question | Surefire fork parallelism | Framework/runner parallelism | Generated runner sharding | ScenarioMesh |
|---|---|---|---|---|
| Who normally owns Maven test execution? | Surefire/Failsafe | Surefire + framework/provider | Surefire + generated runners | ScenarioMesh only after compatibility proof |
| Main parallel unit | fork/JVM plus provider-selected tests | framework-specific tests/nodes | generated runner classes/shards | discovered lifecycle-safe work units |
| Creates forked JVMs? | Yes | May execute inside one or more Surefire forks | Usually executed by Surefire forks | Yes, isolated worker JVM model |
| Can use remote workers? | Not its normal fork model | Usually framework-local | Normally local build sharding unless external system added | Distributed worker architecture supported |
| Understands heterogeneous worker capabilities? | Not as ScenarioMesh worker protocol | No cross-worker fleet model | Usually no | Yes |
| Engine/adapter routing? | Provider selection, not heterogeneous fleet scheduling | Framework-specific | Fixed by generated runners | Task-to-worker capability matching |
| Protocol/auth/TLS between coordinator/workers? | No | No | No | Yes for distributed mode |
| Lease/heartbeat authority? | No ScenarioMesh-style lease model | No | No | Yes |
| Duration/history-aware global scheduling? | Different Surefire mechanisms | Framework-dependent | Usually static shards | ScenarioMesh scheduler can use execution history |
| Explicit "prove then own" Maven pass-through? | Not applicable; Surefire is native owner | Not the same ownership boundary | No | Core invariant |
| Does one Scenario Outline with 5 rows become 5 physical feature files? | No | No | No; a generator may create runner classes | No |
| Can framework lifecycle force grouping? | Provider/framework controls it | Yes | Depends on shard strategy | Explicit lifecycle-aware work-unit model |
| Prevent native duplicate run after takeover? | Native execution itself | Native execution itself | Generated runners are native execution | Yes; native duplicate execution is suppressed only after proof |

---

# 26. Concrete example: same 3 scenarios under three approaches

Our source is still:

```text
smoke.feature
 ├─ Scenario 1
 ├─ Scenario 2
 └─ Scenario 3
```

## A. Surefire forks

```text
mvn test
   │
   ▼
Surefire forkCount=2 (example configuration)
   │
   ├── JVM fork A ──► provider/framework work
   └── JVM fork B ──► provider/framework work
```

Surefire owns the execution and its provider/framework integration determines how the Cucumber workload is exposed/distributed.

## B. Generated Cucumber runners

```text
smoke.feature
   │
   ▼
runner-generation tool
   │
   ├── RunnerA.java
   ├── RunnerB.java
   └── RunnerC.java
        │
        ▼
Surefire sees Java test classes/shards
```

This is a static/generated sharding strategy.

## C. ScenarioMesh

```text
mvn test
   │
   ▼
ScenarioMesh proves takeover compatibility
   │
   ▼
Cucumber/JUnit Platform discovery
   │
   ├── Scenario identity 1
   ├── Scenario identity 2
   └── Scenario identity 3
        │
        ▼
lifecycle-safe work-unit construction
        │
        ▼
capability-aware dynamic scheduler
        │
        ├── Worker A
        ├── Worker B
        └── Worker C
```

No new Gherkin files are required, and generated runner classes are not the fundamental scheduling mechanism.

---

# 27. Why ScenarioMesh can be faster than static fork/runner distribution

Imagine 8 executable cases with historical durations:

```text
A = 120 sec
B = 110 sec
C = 15 sec
D = 14 sec
E = 13 sec
F = 12 sec
G = 11 sec
H = 10 sec
```

A static split can accidentally produce:

```text
Worker 1: A + B                    = 230 sec
Worker 2: C + D + E + F + G + H   =  75 sec
```

Worker 2 becomes idle while Worker 1 remains busy.

A dynamic/duration-aware scheduler can instead keep workers fed with eligible work and try to balance long-running cases, subject to lifecycle and capability constraints.

The important qualification is **subject to lifecycle and capability constraints**. ScenarioMesh should not chase perfect CPU utilization by breaking test correctness.

---

# 28. What ScenarioMesh deliberately does NOT do

```text
ScenarioMesh does NOT:
```

- turn five Cucumber Examples rows into five physical `.feature` files;
- require a new Java runner class for every Cucumber scenario as its core design;
- assume every discovered test node is independently safe to retry;
- assume every worker supports every framework/engine;
- use ScenarioMesh's own JVM blindly instead of respecting the target test runtime;
- replace Jenkins node/agent allocation;
- trust an unregistered remote process merely because it can open a socket;
- let an idle presence heartbeat grant work authority;
- suppress native Maven execution before it has proven compatibility;
- fall back to rerunning the full native Maven test suite after owned work may already have executed.

---

# 29. A mental model to remember

If you remember only one diagram, use this one:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ TARGET REPOSITORY                                                           │
│ Cucumber/JUnit/TestNG tests + normal Maven configuration                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                  mvn test
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ SCENARIOMESH OBSERVES                                                       │
│ "What would Maven actually run, with what JVM, engine, filters and scope?"  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ COMPATIBILITY PROOF                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                │ YES                                      │ NO
                ▼                                          ▼
┌───────────────────────────────────────┐    ┌────────────────────────────────┐
│ TAKE OWNERSHIP                        │    │ PASS THROUGH                    │
│ suppress native duplicate execution  │    │ Maven behaves natively          │
└───────────────────────────────────────┘    └────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ FRAMEWORK-NATIVE DISCOVERY                                                  │
│ exact executable identities, not invented file copies                       │
└─────────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ LIFECYCLE-SAFE WORK UNITS                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ CAPABILITY-AWARE WORKER POOL                                                │
│ local / remote • correct JVM • correct adapter/engine • protocol/security    │
└─────────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ DYNAMIC SCHEDULER                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ORIGINAL FRAMEWORK EXECUTES ORIGINAL TARGET TEST                            │
└─────────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ AUTHORITATIVE RESULTS + REPORTS + MAVEN OUTCOME                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

# 30. Glossary

**Target repository** — The user's existing Java/Maven automation repository that ScenarioMesh is accelerating.

**Ownership** — ScenarioMesh has proven it can safely replace the selected native test execution for this run and therefore becomes responsible for executing/reporting it.

**Pass-through** — ScenarioMesh deliberately leaves the original Maven test execution untouched because takeover safety was not proven or takeover was not selected.

**Adapter** — ScenarioMesh integration contract for a supported test framework/runtime model.

**Engine/provider** — The framework-specific runtime used by JUnit Platform/Surefire/etc. to discover and execute tests.

**Feature file** — Physical Gherkin source such as `smoke.feature`.

**Scenario Outline** — A Gherkin scenario template parameterized by Examples rows.

**Example row** — One data row that produces one executable instance of a Scenario Outline.

**Executable Cucumber case / pickle** — A concrete executable case produced from Gherkin after expansion/filtering; this does not imply a new physical `.feature` file.

**JUnit Platform UniqueId/test descriptor** — Framework/platform identity used to refer precisely to discovered executable/container nodes.

**Work unit** — ScenarioMesh's schedulable unit after lifecycle/scope constraints are applied.

**Worker** — Isolated ScenarioMesh process/JVM that executes assigned work using the target test runtime.

**Capability** — Facts proving a worker can run a particular adapter/engine/runtime requirement.

**Lease** — Coordinator-issued authority for one worker execution attempt/work unit.

**Presence** — Worker liveness information; it is not work authority.

---

# 31. Official background references

For the underlying technologies described above:

- Maven Surefire — Fork Options and Parallel Test Execution: <https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html>
- Cucumber Gherkin reference — Scenario Outline / Examples: <https://cucumber.io/docs/gherkin/reference/#scenario-outline>
- Cucumber parallel execution guide: <https://cucumber.io/docs/guides/parallel-execution/>
- JUnit User Guide: <https://junit.org/junit5/docs/current/user-guide/>

---

# 32. Final summary using our original example

We started with exactly this:

```text
cucumber-junit-platform-example
Java 17
Cucumber 7.20.1
JUnit Platform integration
1 smoke.feature
3 scenarios
mvn test
```

The intended ScenarioMesh execution is:

```text
mvn test
   ↓
ScenarioMesh Maven integration observes the real request
   ↓
reads effective Maven/test runtime and selection
   ↓
recognizes supported Cucumber JUnit Platform execution
   ↓
PROVES compatibility
   ↓
only now takes ownership and prevents duplicate native execution
   ↓
Cucumber/JUnit Platform discovers the 3 original executable scenarios
   ↓
ScenarioMesh preserves lifecycle and creates safe work units
   ↓
workers are prepared and capability-checked
   ↓
scheduler dynamically assigns only compatible work
   ↓
workers invoke the real Cucumber/JUnit Platform engine
   ↓
leases/heartbeats protect distributed execution authority
   ↓
results are validated and aggregated
   ↓
reports are produced
   ↓
Maven receives one final result
```

If at the pre-ownership compatibility stages ScenarioMesh cannot prove that it can preserve the target repository's behavior, the flow changes to:

```text
mvn test
   ↓
ScenarioMesh checks
   ↓
cannot safely prove takeover
   ↓
DO NOT SUPPRESS NATIVE EXECUTION
   ↓
Maven/Surefire/Failsafe/framework continues normally
```

That is the core of ScenarioMesh: **not merely more forks, and not generated Cucumber files, but a safe ownership and dynamic execution runtime layered around the target repository's native test semantics.**
