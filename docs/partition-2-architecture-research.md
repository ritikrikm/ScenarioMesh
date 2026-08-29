# Partition 2 architecture research

Status: research proposal, not an implementation or support claim.

## Executive decision

ScenarioMesh is not architecturally too small for a product. Its current coordinator, leases,
worker lifecycle, runtime isolation, capability routing, protocol negotiation, semantic Maven
gate, adapter SPI, scheduling history, and reporting exporters are useful product foundations.
The main limitation is the domain boundary: the wire model still assumes that all work is a
`ScenarioTask` executed by a `ScenarioAdapter`.

Partition 2 should generalize that boundary rather than replace the distributed core:

1. Introduce a versioned `ExecutionSpec` that can describe fine-grained tests, framework-owned
   capsules, and build-tool-owned capsules.
2. Keep framework launchers as the preferred path when ScenarioMesh can preserve semantics and
   schedule useful fine-grained work.
3. Add a native Maven capsule as the compatibility path for complete plugin executions that are
   unsafe to translate. The worker invokes the target project's Maven and Surefire/Failsafe
   versions; ScenarioMesh does not embed private Surefire internals.
4. Separate execution events, final outcomes, and artifacts. Results must model attempt, shard,
   run, configuration failure, infrastructure failure, and aggregate status independently.
5. Add content-addressed input/artifact transfer only when workers no longer share a workspace.
   Do not turn ScenarioMesh into a general build system or implement the full Remote Execution API.

This produces a generic test-execution platform while retaining a focused Maven product.

## Research questions and conclusions

### Can ScenarioMesh reuse Surefire instead of reproducing each option?

Partly, but there are three materially different choices.

**Embed Surefire provider/booter internals:** rejected as the primary design. Surefire constructs a
provider-specific execution realm, serializes provider configuration, controls a forked JVM, and
uses its own command/event channel. Public Java types such as `ProviderConfiguration`,
`TestRequest`, and `SurefireProvider` expose implementation concepts, but do not constitute a
version-independent distributed execution API. Binding ScenarioMesh to these types would make the
control plane sensitive to the target's Surefire version and classloader topology.

**Invoke the target Maven execution as a subprocess:** recommended for build-tool capsules. It
preserves Maven model interpolation, plugin realms, custom provider dependencies, toolchains,
late `argLine` replacement, lifecycle ordering, and the target's selected Surefire/Failsafe
version. The cost is coarser scheduling and report import instead of direct framework events.

**Invoke native framework APIs:** retain as the preferred fine-grained path. JUnit Platform,
TestNG, and Cucumber understand their own selectors and lifecycle better than a central parser.
This path provides method/scenario scheduling and normalized live results.

The Apache Surefire architecture confirms the separation between Mojo configuration, provider
resolution, booter setup, isolated provider/test classloaders, fork communication, and reporting:
<https://maven.apache.org/surefire-archives/surefire-LATEST/maven-failsafe-plugin/architecture.html>.
The current Surefire parameter model demonstrates why a single generic property-forwarding layer
is insufficient: parameters modify selection, dependency resolution, classpaths, JVM launch,
environment, provider behavior, failure policy, and reports:
<https://maven.apache.org/components/surefire/maven-surefire-plugin/test-mojo>.

### Should ScenarioMesh become a generic remote execution system?

It should adopt a generic internal execution contract, but should not initially expose or
implement a full generic build API. The Remote Execution API models a command, input root,
platform requirements, declared outputs, content digests, execution, and action results. Those are
good design constraints for portable capsules:
<https://github.com/bazelbuild/remote-apis/blob/main/build/bazel/remote/execution/v2/remote_execution.proto>.

ScenarioMesh has test-specific responsibilities absent from a generic command service: framework
identity, logical test identity, discovery materialization, retries classified by cause, flaky
status, lifecycle/configuration failures, test attachments, and Maven build outcome semantics.
Its API should therefore be test-oriented and REAPI-inspired, not REAPI-compatible by assertion.

### What should be generic?

The following concepts should be framework- and build-tool-neutral:

- execution identity and parent/child identity;
- execution kind and semantic owner;
- immutable inputs and workspace requirements;
- command/JVM/framework launch description;
- selectors and opaque owner configuration;
- environment, working directory, toolchain, classpath/module path, and declared outputs;
- platform and resource requirements;
- timeout, cancellation, retry, and idempotency policy;
- event stream, final outcome, and artifacts;
- security identity, secret references, and redaction policy;
- provenance and reproducibility metadata.

Maven model interpretation should remain in the Maven integration. JUnit/TestNG/Cucumber
interpretation should remain in adapters. The coordinator should schedule validated generic work,
not understand POM XML or TestNG XML.

## Proposed execution model

### Execution kinds

`TEST_CASE`
: A stable, independently executable method, scenario, or invocation owned by ScenarioMesh.

`FRAMEWORK_CONTAINER`
: A class, engine container, TestNG suite, Cucumber runner, factory, or dynamic container whose
  lifecycle must remain together. The framework controls internal discovery and execution.

`MAVEN_PLUGIN_EXECUTION`
: A native Maven/Surefire/Failsafe execution. Maven controls effective configuration, plugin realm,
  provider, lifecycle, and reports inside one worker allocation.

`COMMAND`
: Reserve in the schema for internal probes and future product extensions, but do not expose it as
  a supported arbitrary remote-command product in Partition 2.

### Semantic ownership

`SCENARIOMESH`, `FRAMEWORK`, and `BUILD_TOOL` must be explicit and immutable for an attempt. The
owner decides selection, lifecycle, and internal parallelism. ScenarioMesh always owns placement,
lease authority, cancellation, infrastructure retry, event collection, and aggregate reporting.

### ExecutionSpec outline

The exact wire representation requires a protocol ADR, but the semantic fields should include:

```text
identity:
  runId, executionId, parentExecutionId, moduleId, executionPlanId
kind:
  TEST_CASE | FRAMEWORK_CONTAINER | MAVEN_PLUGIN_EXECUTION
owner:
  SCENARIOMESH | FRAMEWORK | BUILD_TOOL
inputs:
  workspaceSnapshot, classpath, modulePath, toolchain, immutableArtifacts
launch:
  executable, arguments, jvmArguments, environment, workingDirectory
selection:
  ownerType, selectors, opaqueOwnerConfiguration
outputs:
  declaredPaths, reportRoots, attachmentRoots, stdoutPolicy
requirements:
  os, architecture, javaRange, adapters, engines, cpu, memory, browserSlots, labels
policy:
  timeout, cancellationGrace, retryClass, maxAttempts, internalParallelism
security:
  principal, secretReferences, networkPolicyRef, redactionPolicy
compatibility:
  schemaVersion, requiredFeatures, optionalFeatures
```

Do not place credentials directly in the spec. Use short-lived, worker-resolved secret references.

### Events, outcomes, and artifacts

The current `ExecutionResult` is appropriate for one terminal logical result but too narrow for
capsules. A capsule needs an append-only event stream plus a terminal aggregate:

- queued, assigned, started, heartbeat, output chunk, test discovered, test started;
- test finished, artifact published, retry scheduled, cancellation requested;
- process exited, capsule finished, infrastructure lost.

Bazel's Build Event Protocol is useful precedent: individual `TestResult` events distinguish
attempt, shard, and run, while `TestSummary` aggregates status and artifacts. It also acknowledges
that announced events may be absent after crashes:
<https://bazel.build/remote/bep> and
<https://docs.bazel.build/versions/main/bep-glossary.html>.

ScenarioMesh should model:

- logical test identity separately from execution attempt identity;
- discovery count separately from materialized runtime invocation count;
- test failure separately from configuration, infrastructure, cancellation, and timeout;
- raw owner artifacts separately from normalized ScenarioMesh reports;
- `FLAKY` as an aggregate across attempts, not a terminal status of one attempt;
- partial/incomplete result streams as first-class failure evidence.

## Native Maven capsule design

### Capsule boundary

The safest initial boundary is one module plus one effective plugin execution and lifecycle intent.
The coordinator may run different independent modules/executions on different workers only when
the Maven execution plan proves that ordering and reactor dependencies permit it. It must not
split a single custom Surefire execution internally.

### Preparation

The Maven integration creates a sanitized capsule manifest containing:

- repository and immutable revision/workspace snapshot identity;
- module path and reactor context required by the execution;
- requested lifecycle/goal and exact execution identity;
- Maven executable/distribution identity and settings/toolchain references;
- user properties with secret values replaced by references;
- expected report roots and invocation nonce;
- recursion-prevention token;
- capability and resource requirements.

Maven Toolchains must remain authoritative for selecting a test JDK. Toolchains exist precisely so
compiler, Surefire, Javadoc, and other plugins can share a JDK independent of the JVM running Maven:
<https://maven.apache.org/plugins/maven-toolchains-plugin/>.

Maven Resolver should remain authoritative for Maven artifact collection/resolution. ScenarioMesh
should not create a second dependency graph algorithm.

### Worker execution

The worker creates an isolated attempt directory, materializes or validates the workspace, resolves
secret references, applies the working directory/environment/network policy, and launches Maven in
a new process group. A private property disables ScenarioMesh takeover in the child while retaining
diagnostics. The worker must capture stdout/stderr without parsing it as the result protocol.

Cancellation must terminate the process tree, allow a bounded report flush grace period, and mark
any unconfirmed results incomplete. A lost lease makes the result non-authoritative even if the
process later completes.

### Result import

Import must be adapter-based and version-tolerant:

- Surefire/Failsafe XML and text reports;
- dump and dumpstream diagnostics;
- Cucumber/TestNG/framework-native reports when configured;
- stdout/stderr and declared attachments;
- Maven exit code and lifecycle failure details.

Never infer success only from XML. Require a coherent combination of process exit, expected report
presence, parser success, and capsule completion marker. Preserve raw artifacts for audit.

### Recursion and duplicate ownership

The child Maven invocation must not launch a second ScenarioMesh coordinator. The disabling marker
must be unforgeable across a remote request boundary, scoped to the capsule, and visible to both the
core extension and plugin. Native Surefire remains enabled inside the capsule. The parent invocation
must suppress only the exact execution delegated to the worker.

### Why not invoke only `surefire:test`?

Directly invoking a goal can omit preceding lifecycle work, active execution context, generated
test resources, agent preparation, or properties changed by earlier plugins. Capsules need an
execution-plan-aware strategy. Initial support should prefer prepared workspaces after
`test-compile` for Surefire and lifecycle-scoped module execution for Failsafe. Non-standard plans
must stay unsupported until an experiment proves equivalent ordering.

## Workspace and artifact architecture

### Shared filesystem mode

Keep the current mode for local workers and agents that already possess an equivalent workspace.
Strengthen the runtime fingerprint into separate identities for source snapshot, generated outputs,
classpath, toolchain, and executor configuration. A single aggregate fingerprint is useful for
routing but insufficient for explaining a mismatch.

### Snapshot mode

Remote generic workers need an immutable input manifest. Adopt digest-addressed files/directories,
deduplication, declared writable/output paths, and verified materialization. These mirror useful
REAPI constraints without requiring an REAPI service.

Phases:

1. manifest plus archive transfer;
2. local worker content cache keyed by digest;
3. optional external CAS adapter;
4. evaluate REAPI CAS interoperability only after the internal semantics stabilize.

Do not cache test results by default. Browser, network, time, external service, and mutable data
dependencies make most Maven tests non-hermetic. Result caching requires an explicit hermeticity
contract and should be a separate future decision.

Bazel's test contract demonstrates the value of declared working directories, temporary paths,
outputs, sharding metadata, and controlled environment, while also making hermeticity an explicit
requirement rather than an assumption:
<https://bazel.build/versions/9.0.0/reference/test-encyclopedia>.

## Scheduling and resource model

The current lane-aware FIFO/history scheduling can remain, but eligibility must evolve from adapter
IDs to a structured requirement expression:

- Java/Maven/toolchain version and vendor constraints;
- OS, architecture, filesystem, container/runtime labels;
- adapters, engines, browsers, drivers, display/GPU availability;
- CPU, memory, disk, browser/Grid slots, and exclusive resources;
- network policy and permitted service endpoints;
- workspace/cache locality and artifact transfer cost;
- trust domain and secret-access entitlement.

Resources need scalar reservations and named locks. A TestNG capsule with ten internal threads or
five browsers must reserve that capacity before dispatch. History should estimate duration, memory,
and artifact-transfer cost, but must never change test identity or correctness.

Fairness should eventually include tenant/project quotas and aging. Speculative execution may help
long-tail tests, but only for tasks declared side-effect-safe; it is inappropriate as a default for
Selenium or integration tests.

## Reliability model

### Delivery semantics

The network can provide at-least-once delivery, not exactly-once side effects. ScenarioMesh should
promise exactly one authoritative accepted result per `(executionId, attempt)` through lease fencing
and idempotent result acceptance. It cannot promise that user test side effects occurred once.

Add a monotonically increasing fencing token or lease epoch to future work authority. Reject stale
heartbeats, events, artifacts, and terminal results after reassignment. Persist coordinator run
state before supporting coordinator restart/resume.

### Retry policy

Separate:

- dispatch retry before execution acknowledgment;
- infrastructure retry after worker/process loss;
- framework rerun requested by Surefire/framework configuration;
- user-requested flaky retry;
- speculative duplicate execution.

These must produce different attempt identities and report semantics. Framework reruns belong
inside a framework/build-tool capsule unless ScenarioMesh explicitly owns them.

### Backpressure

Large logs and artifacts must not share an unbounded in-memory path with lease heartbeats. Use
bounded event queues, separate artifact transfer, flow control, maximum sizes, and truncation with
an explicit artifact status. The current maximum frame size is a good control-plane safeguard; it
should not become the artifact transport limit.

## Security model

Workers execute untrusted repository code. Authentication and TLS protect transport but do not
sandbox tests. Product deployment needs explicit trust tiers:

- trusted local developer worker;
- trusted CI agent with workspace access;
- isolated ephemeral worker for untrusted code;
- privileged resource worker for browsers/devices/secrets.

Required controls include least-privilege service identity, short-lived credentials, workspace and
process isolation, egress policy, secret scoping, artifact size/type policy, dependency provenance,
audit events, and secure deletion. Container support is a deployment adapter, not itself a complete
sandbox.

SPIFFE is a suitable optional integration model for dynamically issued workload identity and mTLS;
its Workload API is designed for platform-neutral process/workload identity:
<https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Workload_API.md>. Do not require SPIFFE
for local use, and do not invent a proprietary identity format that prevents later integration.

## Observability and product API

Use stable run, execution, attempt, worker, lease, module, framework, and artifact IDs across logs,
metrics, traces, and reports. OpenTelemetry CI/CD conventions now model pipeline runs and workers,
including distributed workers, and should be the external naming baseline where applicable:
<https://opentelemetry.io/docs/specs/semconv/cicd/>.

Expose an event API rather than coupling integrations to console logs or final HTML. Event schema
compatibility must be independent of the worker command protocol. Store large payloads externally
and reference them by digest/URI with authorization.

## Protocol evolution

Do not keep adding optional fields to one envelope indefinitely. Partition the next protocol into:

- session/negotiation messages;
- work authority and lease messages;
- execution specifications;
- execution events and terminal outcomes;
- artifact metadata/transfer;
- worker inventory and telemetry.

Use feature negotiation in addition to an integer version. Required features fail registration or
dispatch; optional features permit downgrade. Unknown fields should be tolerated only where their
absence cannot alter correctness. Keep v8/v9 bridging while introducing the new protocol behind a
new negotiated feature/version; do not reinterpret old `ScenarioTask` payloads.

JSON remains acceptable for the existing control protocol. Before high-volume event/artifact work,
benchmark framed JSON against protobuf/gRPC. Choose a transport based on measured CPU, memory,
backpressure, streaming, compatibility, and operational cost, not fashion. Artifact transfer should
be independent of this choice.

## Maven 4 strategy

Maven 4 is not merely Maven 3 with a new version number. Keep Maven-facing code behind an
integration abstraction and compile/test separate compatibility implementations where APIs differ.
Do not expose Maven model classes in core execution contracts. Preserve plugin execution priority,
effective model behavior, toolchains, project/session scope, and lifecycle semantics through
versioned integration tests. Maven 4's API includes explicit plugin execution model and lifecycle
types, but ScenarioMesh should use only documented/stable surfaces where possible:
<https://maven.apache.org/ref/4-LATEST/api/maven-api-model/maven.html>.

The product claim should name qualified Maven releases and matrices, not “all Maven 4+” without an
upper bound. Future Maven releases and Surefire versions can add semantic parameters, so unknown
configuration must route to a native capsule or pass-through until qualified.

## Alternatives considered

### Reimplement all Surefire parameters

Rejected. It duplicates a moving plugin/provider implementation and creates silent semantic drift.

### Always run native Maven remotely

Rejected as the only mode. It maximizes compatibility but loses scenario/method-level scheduling,
live normalized results, efficient retries, and framework-aware resource placement.

### Always use framework launchers

Rejected as the only mode. Framework APIs cannot reproduce arbitrary Maven plugin realms,
dependency scanning, lifecycle preparation, agents, custom providers, and report integrations.

### Embed target Surefire in the worker JVM

Rejected initially. Version conflicts, static state, classloader leakage, `System.exit`, and private
booter assumptions undermine worker reuse and control-plane isolation.

### Adopt REAPI as the public API now

Rejected for Partition 2. REAPI is command/action oriented and does not supply ScenarioMesh's test
identity and lifecycle semantics. Internal alignment and optional CAS interoperability are valuable.

### Replace the current protocol immediately with gRPC

Rejected without measurement. Protocol shape and execution semantics should stabilize first.

## Phased Partition 2 roadmap

### P2.0: Architecture contracts and experiments

- ADR for `ExecutionSpec`, ownership, events/outcomes/artifacts, and feature negotiation.
- Rename-neutral migration path from `ScenarioTask`; do not perform a flag-day rewrite.
- Golden protocol fixtures and mixed-version tests.
- Spike one native Surefire capsule on a prepared single-module workspace.
- Measure Maven startup, report latency, logs, cancellation, process-tree cleanup, and failure modes.
- Compare direct goal invocation with lifecycle invocation on projects using JaCoCo `argLine`,
  generated tests/resources, toolchains, and custom providers.

Exit criterion: evidence supports a safe capsule boundary and no duplicate execution.

### P2.1: Generic execution substrate

- Add execution kind/owner/requirements/policy while adapting old scenario tasks internally.
- Add attempt identity, fencing epoch, structured events, artifact metadata, and bounded output.
- Add scalar/named resource reservations and explicit internal parallelism.
- Keep existing adapters and v8/v9 paths behaviorally unchanged.

Exit criterion: existing fine-grained suites pass through the new internal abstraction.

### P2.2: Native Maven capsule beta

- Single module, standard Surefire execution first.
- Exact Maven distribution/toolchain and isolated process group.
- Recursion prevention and exact parent execution suppression.
- Raw artifact preservation plus Surefire report import.
- Cancellation, timeout, lease loss, infrastructure retry, and incomplete-result handling.

Exit criterion: native local Maven and remote capsule are semantically equivalent across the
qualified matrix, including failures and crashes.

### P2.3: Failsafe and execution plans

- Standard integration-test/verify lifecycle capsules.
- Multiple standard executions where dependency/order isolation is proven.
- Deferred failure semantics and downstream report compatibility.
- Non-standard execution experiments before support.

### P2.4: Portable workspaces and enterprise controls

- Snapshot manifest and digest verification.
- Worker content cache and optional CAS adapter.
- workload identity integration, policy hooks, secret references, tenant quotas, and audit export.
- ephemeral/container/Kubernetes worker providers as deployment modules.

## Mandatory experiment matrix

Before claiming native capsule support, test at least:

- qualified Maven 3 and Maven 4 releases;
- qualified Surefire/Failsafe provider versions;
- Java toolchains and Maven-runtime/test-runtime JDK mismatch;
- JUnit Platform, generic JUnit 4/Vintage, TestNG XML, Cucumber JUnit 4/Platform;
- custom provider/plugin dependencies;
- JaCoCo and other `argLine` agents with late property replacement;
- generated test sources/resources and pre-test plugin mutations;
- includes/excludes/files, groups, engines, dependencies-to-scan, and classpath modifications;
- multiple executions, multi-module reactor, test-JAR dependencies, and Failsafe verify;
- environment, working directory, module path, assertions, listeners, reporters, reruns;
- pass, skip, assumption, test failure, configuration failure, no tests, timeout, cancellation;
- worker death, coordinator disconnect, stale lease result, corrupt/partial XML, huge logs/artifacts;
- Selenium local driver and Grid, limited browser capacity, screenshots, downloads, and leaked child
  processes;
- Linux, macOS, and Windows path/process behavior where claimed.

## Immediate decisions

1. Keep the existing distributed core; generalize its work/result boundary incrementally.
2. Keep fine-grained native framework adapters as a product differentiator.
3. Use subprocess Maven capsules, not embedded Surefire internals, for complex compatibility.
4. Do not promise arbitrary Maven 4+ compatibility; publish a qualified matrix.
5. Separate control, events, and artifacts before adding portable workspaces.
6. Treat untrusted test execution as a security boundary, not just a TLS problem.
7. Require native-equivalence fixtures for every transition from pass-through to ownership/capsule.
8. Do not remove local pass-through until the corresponding remote capsule is proven safe.

## Open decisions requiring spikes

- prepared `test-compile` workspace versus complete lifecycle capsule for Surefire;
- module-level reactor materialization and artifact handoff;
- event persistence technology and coordinator restart guarantees;
- archive manifest versus Merkle directory representation for snapshots;
- artifact upload protocol and storage provider SPI;
- exact protocol v10 representation and whether protobuf/gRPC earns its operational cost;
- how browser/Grid reservations are discovered and fenced across independent coordinators;
- which custom provider/plugin configurations are safe enough for capsule beta;
- minimum sandbox requirements for a hosted/multi-tenant ScenarioMesh service.

