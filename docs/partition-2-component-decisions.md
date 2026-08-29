# Partition 2 component decisions

Status: implementation input. Versions and production suitability remain subject to experiments.

The rule is to adopt maintained upstream components for build-tool semantics and commodity
transport/storage behavior while retaining ScenarioMesh-specific lease, ownership, test identity,
and result semantics.

## Maven invocation

### Apache Maven Executor

Decision: **prototype the forked provider**.

Apache Maven Executor provides a dependency-light API for Maven 3 and Maven 4 and includes forked
and embedded implementations, plus Docker CLI and Testcontainers providers. The project states that
it is intended to replace Maven Invoker and Maven Verifier:
<https://github.com/apache/maven-executor>.

Why it fits:

- Apache-owned and aligned with current Maven 3/4 integration testing;
- avoids maintaining Maven executable discovery and command-line compatibility alone;
- leaves the target Maven distribution in control;
- optional providers create a future deployment seam without making containers mandatory.

Conditions:

- pin and qualify exact releases;
- inspect argument redaction and environment inheritance;
- verify cancellation and descendant-process behavior;
- wrap it behind a ScenarioMesh SPI so it can be replaced;
- do not allow its result object to become the ScenarioMesh wire contract.

### Maven Invoker

Decision: **do not begin new integration on it**.

It is a mature forked-Maven facade, but its current request API is deprecated and Maven Executor is
the Apache successor. It remains useful as behavioral precedent, not the preferred new dependency:
<https://github.com/apache/maven-invoker/blob/master/src/main/java/org/apache/maven/shared/invoker/InvocationRequest.java>.

### Embedded Maven

Decision: **reject for capsule beta**.

Embedding risks static-state leakage, classworld/plugin realm conflicts, `System.exit`, incompatible
Maven versions, and weaker process cleanup. It may be benchmarked later for trusted local mode but
must never be required for compatibility.

## Surefire/Failsafe execution

### Surefire provider and booter internals

Decision: **do not embed or mirror them**.

Surefire's Mojo resolves providers and constructs provider/booter configuration tied to the plugin
version and execution realm. ScenarioMesh will invoke the target Maven execution instead:
<https://maven.apache.org/surefire-archives/surefire-LATEST/maven-failsafe-plugin/architecture.html>.

### Surefire Report Parser

Decision: **prototype behind a version-neutral report-import SPI**.

Apache publishes `surefire-report-parser` specifically to parse Surefire report files, including
flaky failure/error data in current releases:
<https://maven.apache.org/surefire/surefire-report-parser/apidocs/org/apache/maven/plugins/surefire/report/SurefireReportParser.html>.

Conditions:

- raw reports remain authoritative artifacts and are always retained;
- Maven exit status and a capsule completion marker are evaluated in addition to parsed XML;
- corrupt, missing, stale, duplicate, or partial reports produce explicit incomplete/configuration
  outcomes rather than guessed success;
- imported API objects are translated immediately into ScenarioMesh-owned immutable values;
- qualify parser behavior against older and newer target report formats.

If the upstream parser cannot safely parse the qualified matrix, implement a small hardened XML
reader behind the same SPI rather than exposing parser-version classes to core.

## Dependency and toolchain resolution

### Maven Resolver and effective Maven model

Decision: **reuse through the Maven integration; do not resolve independently in workers**.

The parent Maven invocation is authoritative for effective models, repositories, mirrors,
credentials, dependency collection, reactor artifacts, and plugin executions. A capsule should use
the target Maven process when these semantics are required. Portable workspace work may later carry
resolved immutable artifacts, but will not implement another Maven conflict-resolution algorithm.

### Maven Toolchains

Decision: **preserve the selected toolchain and verify it on the worker**.

Toolchains intentionally allow compiler, Surefire, and other plugins to use a JDK independent of
the JVM running Maven: <https://maven.apache.org/plugins/maven-toolchains-plugin/>.

The worker must not silently replace an unavailable selected toolchain. It either satisfies the
requirement, materializes an approved toolchain, or rejects eligibility.

## Process supervision

### Maven Shared Utils command execution

Decision: **do not use as the authority model**.

Its command utility provides streams and timeout handling, but ScenarioMesh additionally needs
lease fencing, asynchronous cancellation, process-tree cleanup, bounded logs, and attempt evidence:
<https://maven.apache.org/shared/maven-shared-utils/apidocs/org/apache/maven/shared/utils/cli/CommandLineUtils.html>.

Use Maven Executor for Maven construction and a ScenarioMesh process-supervisor boundary for
authority. Prefer Java `ProcessHandle` plus tested OS-specific escalation before adding a broader
process library. Re-evaluate after Windows/Linux/macOS failure experiments.

## Remote execution and artifacts

### Bazel Remote Execution API

Decision: **adopt concepts, not the complete API**.

Commands, input roots, platform requirements, declared outputs, digests, and action results are
valuable constraints. REAPI does not model framework lifecycle, logical test identity, discovery,
configuration failures, or Maven build outcomes:
<https://github.com/bazelbuild/remote-apis/blob/main/build/bazel/remote/execution/v2/remote_execution.proto>.

Do not implement a CAS until non-shared-workspace experiments require it. When needed, define a
ScenarioMesh artifact-store SPI and evaluate an REAPI CAS adapter rather than building a mandatory
proprietary store.

### Build Event Protocol

Decision: **use as result/event design precedent, not a wire dependency**.

BEP separates individual test attempt/shard/run results from aggregate summaries and references
artifacts independently: <https://bazel.build/remote/bep>.

ScenarioMesh needs the same separation but has different build and framework semantics.

## Transport and schema

### gRPC and Protocol Buffers

Decision: **defer adoption pending P2.1 benchmarks**.

They offer streaming, flow control, generated compatibility, and mature observability/security
integrations. Replacing the current transport before the execution/event/artifact semantics settle
would combine two migrations and endanger the proven v8/v9 bridge.

Define transport-neutral contracts first. Benchmark framed JSON and protobuf/gRPC with realistic
logs, event counts, heartbeats, cancellation, and mixed versions. Artifacts must not be transferred
inside control frames in either design.

## Containers and sandboxing

### Docker/Testcontainers/Kubernetes

Decision: **deployment providers, not core semantics or security claims**.

Maven Executor already exposes Docker CLI and Testcontainers providers that should be evaluated for
ephemeral capsule experiments. Kubernetes should later implement worker provisioning and resource
advertisement, not change execution meaning.

A container alone is not a sufficient multi-tenant sandbox. Network, secrets, filesystem, kernel,
resource limits, image provenance, and workload identity require separate policy.

## Observability and identity

### OpenTelemetry

Decision: **continue integration and align external fields with CI/CD conventions**.

Run, execution, attempt, worker, lease, module, and artifact IDs remain ScenarioMesh concepts, while
published spans/metrics should use applicable OpenTelemetry CI/CD naming:
<https://opentelemetry.io/docs/specs/semconv/cicd/>.

### SPIFFE/SPIRE

Decision: **optional enterprise workload-identity integration**.

SPIFFE's Workload API provides platform-neutral, dynamically issued workload identity suitable for
mTLS: <https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Workload_API.md>.

Do not require it for local mode. Keep identity interfaces capable of representing SPIFFE IDs and
short-lived credentials without inventing a competing mandatory identity format.

## Re-evaluation rule

Every adopted library must pass:

- license and maintenance review;
- supported Java/Maven compatibility;
- dependency/classloader isolation review;
- cancellation, timeout, crash, and malformed-input tests;
- secret redaction and supply-chain review;
- replacement through a ScenarioMesh-owned interface;
- native-equivalence tests before production ownership changes.

