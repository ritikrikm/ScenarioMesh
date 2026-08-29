# ADR: Generic execution contract

Status: accepted for incremental implementation; protocol representation remains experimental.

## Context

The current core and v8/v9 protocol model work as `ScenarioTask` values executed by a
`ScenarioAdapter`. That accurately represents the implemented fine-grained path but cannot safely
represent a framework-owned suite or a native Maven/Surefire/Failsafe execution without encoding
unrelated behavior in task metadata.

Partition 2 must add broader compatibility without rewriting the proven coordinator, scheduler,
lease authority, worker lifecycle, adapter execution, or rolling protocol bridge.

## Decision

Introduce an internal, versioned execution contract with these independent dimensions:

- kind: `TEST_CASE`, `FRAMEWORK_CONTAINER`, or `MAVEN_PLUGIN_EXECUTION`;
- semantic owner: `SCENARIOMESH`, `FRAMEWORK`, or `BUILD_TOOL`;
- stable execution identity and parent identity;
- immutable input/workspace description;
- launch environment and toolchain;
- owner-specific selectors and opaque configuration;
- declared outputs and artifact policy;
- platform, capability, and scalar/named resource requirements;
- timeout, cancellation, retry, and internal-concurrency policy;
- required and optional protocol features.

`ScenarioTask` remains the compatibility representation for the existing v8/v9 fine-grained path.
The first implementation will adapt a `ScenarioTask` to the new internal contract. It will not
rename every public type or reinterpret an old wire payload.

The contract is test-oriented. It may borrow command/input/platform/output concepts from the Remote
Execution API, but it does not claim REAPI compatibility and does not expose arbitrary remote code
execution as a ScenarioMesh product feature.

## Ownership rules

The semantic owner controls selection, lifecycle, and internal parallelism. ScenarioMesh always
controls placement, lease authority, cancellation, infrastructure retry, event acceptance, and
aggregate reporting.

An execution has exactly one semantic owner for an attempt. Ownership cannot change after dispatch.
Moving work from pass-through to any ownership mode requires native-equivalence evidence.

## Result model

Execution events, terminal attempt outcomes, logical aggregate outcomes, and artifacts are separate
concepts. Attempt identity is distinct from logical test identity. Framework reruns, infrastructure
retries, and speculative attempts must not collapse into one counter.

The coordinator accepts exactly one authoritative terminal outcome for an execution attempt. Lease
fencing cannot guarantee exactly-once user side effects and the product must not make that claim.

## Compatibility

- v8/v9 messages retain their current meaning.
- New fields that affect correctness require negotiated features, not silent defaulting.
- Required unsupported features reject dispatch.
- Optional unsupported features may be omitted only when omission cannot change semantics.
- Golden serialization and mixed-version tests are required before a new wire version ships.

## Consequences

The existing distributed core can evolve incrementally and adapters remain useful. The coordinator
will eventually schedule heterogeneous execution kinds rather than knowing framework details.
There is additional schema and migration complexity, but it is explicit instead of being hidden in
metadata strings.

