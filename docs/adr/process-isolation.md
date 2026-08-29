# ADR: process isolation

## Status
Accepted architectural direction; implementation completeness is stated in README.

## Decision
ScenarioMesh uses this decision to preserve correctness, isolation and replaceability at module boundaries.

## Alternatives considered
Tighter coupling and implicit behavior were rejected because they make framework upgrades and CI diagnosis harder.

## Trade-offs
The design adds explicit types and lifecycle machinery, but makes failure modes observable and future implementations replaceable without changing core scheduling semantics.
