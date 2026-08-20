# ScenarioMesh init command plan — 2026-08-20

Branch: `agent/init-command`

## Goal

Provide a one-time repository bootstrap command so users do not manually author `.mvn/extensions.xml` or a ScenarioMesh config file. After initialization, the project continues to use normal Maven commands such as `mvn test` locally and in CI/Jenkins.

## Safety contract

1. Detect and validate the Maven project before any writes.
2. Build an immutable change plan before mutating the filesystem.
3. Preserve unrelated Maven extension entries.
4. Never duplicate the ScenarioMesh extension entry.
5. Update an existing ScenarioMesh extension version deliberately rather than appending another entry.
6. Preserve an existing `scenariomesh.yml` or `scenariomesh.yaml` byte-for-byte.
7. Fail closed when both YAML names exist, existing Maven XML is malformed, duplicate ScenarioMesh extension entries exist, or the target is not a valid Maven project.
8. Use atomic per-file replacement and rollback already-applied changes if a later write fails.
9. Re-running init against an already-correct repository must be a no-op.
10. `--dry-run` must produce the same plan without writing files.

## Current command surface

```text
scenariomesh init
scenariomesh init /path/to/project
scenariomesh init --project /path/to/project
scenariomesh init --dry-run
scenariomesh version
scenariomesh doctor
```

The CLI artifact is executable with `java -jar scenariomesh-cli-<version>.jar ...`; distribution/install packaging can later expose the same main class as a native `scenariomesh` launcher without changing init logic.

## Files init owns

- `.mvn/extensions.xml`: add/update only the `io.scenariomesh:scenariomesh-maven-extension` entry while retaining other extensions.
- `scenariomesh.yml`: create only when neither `.yml` nor `.yaml` already exists. The generated file contains only `configVersion: 1`, allowing runtime defaults to remain centralized in `ScenarioMeshConfig` rather than copied into the initializer.

## Test matrix

- clean Maven repo => extension + minimal YAML created;
- second init => no changes;
- existing unrelated Maven extension => preserved;
- existing ScenarioMesh entry => updated in place, never duplicated;
- already-correct ScenarioMesh entry => byte-stable no-op;
- malformed `pom.xml` => fail before writes;
- wrong `pom.xml` root => fail before writes;
- malformed `.mvn/extensions.xml` => fail before writes;
- both `scenariomesh.yml` and `scenariomesh.yaml` => fail closed;
- existing custom YAML => preserve byte-for-byte;
- paths containing spaces => supported;
- packaged CLI JAR => version metadata resolved and `init` works;
- dry run => planned changes shown, no files created;
- Java 17 and Java 21 CI => same behavior.

## Future-compatible boundaries

The planner, XML model, and applier are separate from CLI argument parsing so future installers, IDE integrations, or a native launcher can reuse the same initialization logic. Worker auto-sizing is intentionally not part of init; it remains a separate resource-planning feature.
