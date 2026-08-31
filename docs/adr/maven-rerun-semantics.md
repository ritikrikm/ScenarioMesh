# Maven rerun semantics

## Decision

ScenarioMesh models Maven test reruns as attempts of one logical test identity. Maven rerun indexes are deliberately separate from the existing `ExecutionResult.attempt`, which is infrastructure/lease retry state.

The model is:

- logical task identity: stable across every Maven rerun;
- rerun index 0: initial test execution;
- rerun index 1..N: `rerunFailingTestsCount` attempts;
- infrastructure retries: may happen inside any one logical attempt and do not consume Maven rerun budget;
- first later pass: logical status is flaky and reruns stop;
- all test attempts fail: logical status is failed and the first failure remains canonical;
- pass/skip/infrastructure/configuration outcomes are never converted into Maven reruns.

This mirrors the externally documented Surefire/Failsafe contract and leaves room for provider-specific report materialization (`flakyFailure`, `flakyError`, `rerunFailure`, `rerunError`) without multiplying Maven-selected test counts.

## Compatibility gate

The Maven compatibility gate must remain fail-closed until execution, reporting and build-result semantics consume this model end to end. Parsing `rerunFailingTestsCount` alone is not sufficient to claim compatibility.

Future support for `skipAfterFailureCount` and `failOnFlakeCount` must operate on logical executions. Failures occurring during a rerun phase must not increment skip-after-failure accounting.
