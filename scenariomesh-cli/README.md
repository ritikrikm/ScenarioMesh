# scenariomesh-cli

Command-line product surface for ScenarioMesh operations outside direct Maven lifecycle invocation.

## Responsibilities

The CLI provides commands such as initialization, doctor/diagnostic checks, and explicit run-oriented workflows. It should reuse shared configuration, Maven integration planning, and runtime services rather than implementing a second execution engine.

## Intended flow

```text
scenariomesh <command>
        ↓
parse command/options
        ↓
resolve project + ScenarioMesh configuration
        ↓
shared init / doctor / execution service
        ↓
human-readable result + correct exit code
```

`init` should make minimal, reviewable project changes. `doctor` should explain compatibility and environment problems without mutating the project. CLI failures must preserve actionable diagnostics and must never require secrets to be printed.
