# Historical Snapshot: ScenarioMesh Failure/False-Positive Hardening Plan — 2026-08-20

> **Superseded plan.** This document records the starting state of the August hardening work and is not the current product contract. Consult the README, current ADRs, and CI workflows for current support. In particular, later work added JUnit Vintage ownership, compatible Failsafe ownership, logical rerun semantics, and selected multi-engine JUnit Platform coverage.

Branch: `agent/worker-hardening-test`

Goal: eliminate paths where ScenarioMesh can silently skip, misclassify, duplicate, or incorrectly accept work. The safety rule is: before takeover, uncertainty means Maven pass-through; after takeover, uncertainty means an explicit non-success result, never a guessed green.

## Limitation 1 — JUnit Platform terminal semantics

### Failure risk
A selected JUnit Platform test can be found but skipped/aborted. Treating `failed == 0` as `PASSED` is a false-positive classification.

### Test plan before fix
- passing test => `PASSED`;
- assertion failure => `TEST_FAILURE`;
- assumption-aborted test => explicit non-pass terminal status, while preserving Maven-compatible build semantics;
- disabled/skipped selection must never be labelled `PASSED`;
- selected unique id that executes nothing => `INFRASTRUCTURE_FAILURE`.

### Design
Add explicit result statuses for non-executed test outcomes and central build-success semantics. Reporting and Maven outcome logic must understand them instead of overloading `PASSED`.

## Limitation 2 — TestNG discovery silently swallowing class-load failures

### Failure risk
A candidate test class can fail to load and be silently omitted, allowing an incomplete suite to go green.

### Test plan before fix
- valid TestNG test class is discovered;
- non-candidate helper classes do not become fatal simply because they are not tests;
- a class selected by the Maven-compatible class-selection boundary that cannot be inspected produces discovery failure evidence rather than disappearing;
- error contains class name + root cause.

### Design
Never use an empty catch for candidate discovery. Aggregate candidate-inspection failures and fail discovery with actionable evidence.

## Limitation 3 — TestNG skipped semantics

### Failure risk
`onTestSkipped` increments the executed count; a skipped test without a throwable can currently fall through to `PASSED`.

### Test plan before fix
- success => `PASSED`;
- assertion failure => `TEST_FAILURE`;
- skip with throwable => explicit `SKIPPED`, not pass/failure guessing;
- skip without throwable => explicit `SKIPPED`;
- configuration failure/no selected method execution => infrastructure failure.

### Design
Track success/failure/skipped/configuration outcomes independently and classify explicitly.

## Limitation 4 — Worker result is not strongly correlated to dispatched task

### Failure risk
An adapter/protocol bug can return a result for a different scenario, worker, or attempt and the coordinator currently accepts the envelope too early.

### Test plan before fix
Reject as infrastructure/protocol failure when any of these differ from the dispatch: scenario id, worker id, attempt, display identity required by contract, null/invalid timestamps, negative duration, finish-before-start. Valid result remains accepted.

### Design
Introduce one coordinator-owned result validator. Do not duplicate validation across adapters.

## Limitation 5 — Duplicate discovered ScenarioTask ids

### Failure risk
Adapter-local `seen` sets are useful optimizations but cannot be correctness boundaries. A future adapter can forget them or two discovery paths can still collide.

### Test plan before fix
- unique task ids pass validation;
- same id twice fails before WorkerPool creation;
- failure message identifies duplicate id(s);
- adapter-local `seen` behavior remains allowed but is not relied upon.

### Design
Add a central discovery invariant validator immediately after discovery and before any worker starts.

## Limitation 6 — Maven takeover safety

### Failure risk
If compatibility classification is too optimistic, ScenarioMesh can suppress Surefire/Failsafe and then execute semantics that differ from normal Maven.

### Test plan before fix
- reviewed default Surefire configuration can take over;
- unknown plugin configuration => pass-through;
- custom provider dependency/execution => pass-through;
- unsupported selection property => pass-through;
- mixed framework ownership that ScenarioMesh cannot prove complete => pass-through;
- pass-through leaves original executor unsuppressed.

### Design
Preserve fail-closed compatibility. Extend detection only where equivalence is proven; never broaden takeover as a shortcut.

## Limitation 7 — Discovery completeness / test roots

### Failure risk
Using only `project.build.testOutputDirectory` can miss additional compiled test roots generated/added by Maven tooling.

### Test plan before fix
- standard test output is included;
- every existing test-classpath directory representing project test output is considered as a discovery root;
- dependency JARs are not treated as local test roots;
- roots are normalized/deduplicated;
- empty/nonexistent paths do not become roots.

### Design
Derive discovery roots from the effective project test classpath plus known project build outputs, while keeping runtime classpath broader than discovery roots.

## Limitation 8 — Mixed-framework ownership / JUnit Vintage boundary

### Failure risk
A repository may contain JUnit 5 plus direct JUnit 4/Vintage, or multiple supported frameworks. Taking over only the subset an adapter sees can silently omit tests.

### Test plan before fix
- direct generic JUnit 4 alone => pass-through;
- JUnit 5 + direct generic JUnit 4 => pass-through until generic JUnit 4 execution is supported;
- JUnit 5 + TestNG (multiple supported owners) => pass-through unless explicit multi-adapter ownership is implemented;
- supported single-framework repositories retain takeover;
- Cucumber JUnit 4 special adapter remains independently supported.

### Design
Compatibility gating must reason about complete suite ownership, not only whether at least one supported framework exists. Mixed ownership is pass-through until multi-adapter execution is intentionally implemented.

## Verification gates

For every limitation:
1. implement focused unit/regression coverage;
2. add E2E fixture/check when the behavior crosses Maven/worker boundaries;
3. run full reactor and existing E2E suite on Java 17 + Java 21 through CI;
4. update this document/status with the exact result and commit/run evidence.

Future `scenariomesh init` work is intentionally excluded from this branch and will be implemented separately after this hardening pass.
