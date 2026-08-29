# Release and compatibility strategy

## Runtime baseline

ScenarioMesh runtime requires Java 17 or newer. The release gate covers Java 17, Java 21, and the current Java 25 LTS smoke lane.

The production Maven support line is Maven 3.9.x. The release matrix pins the current GA Maven 3.9.16 for an exact-version gate in addition to the GitHub runner Maven used by the broader workflows.

Maven 4 is not currently a production support claim. Apache Maven 4.0.0-rc-6 is a release candidate, not GA, so ScenarioMesh runs a non-blocking preview compatibility job. Once Maven 4 reaches GA, promotion to a supported line requires the normal native-equivalence laboratory before takeover is enabled for Maven-4-specific semantics.

## Versioning

ScenarioMesh uses semantic-version-style product versions:

- patch: compatible bug fixes/security fixes with no intended public configuration/protocol contract break
- minor: additive adapters, reporters, configuration, diagnostics, or compatible protocol capabilities
- major: intentional incompatible configuration, extension, SPI, or distributed-protocol changes

A snapshot build is not a production release.

## Distributed protocol

Coordinator and worker protocol compatibility is independently versioned from the product version. The current implementation rejects an unsupported protocol version at registration rather than guessing compatibility. A future rolling-upgrade window must be introduced explicitly with negotiated version ranges and cross-version fixtures before mixed coordinator/worker versions are supported.

Until that negotiation exists, upgrade coordinator and workers together. This is a deliberate fail-closed release boundary, not an implicit compatibility promise.

## Compatibility promotion rule

A repository/runtime combination moves from native pass-through to ScenarioMesh takeover only after semantic equivalence is proven for:

- selected and executed logical tests
- stable identities and duplicate/missing counts
- pass/fail/skip/abort outcomes
- lifecycle counts and relevant side effects
- build exit semantics
- required downstream reports

Ordering and wall-clock duration may differ only where the underlying test framework permits them.

Any combination that cannot satisfy that proof remains native Maven.

## Release gate

A release candidate should not be published as production-ready unless the required workflows are green for the release commit, including:

- full CI
- lifecycle equivalence
- Maven/JVM compatibility
- framework SPI/backend inventory
- distributed contracts and TLS
- multi-agent simulation
- scheduler/history
- observability
- reporting integrations and downstream report compatibility
- CLI/product tests
- external target smoke
- current LTS / Maven GA runtime matrix

Preview lanes such as Maven 4 RC are informative until their upstream runtime is GA and ScenarioMesh explicitly promotes support.
