# Release and compatibility strategy

## Runtime baseline

ScenarioMesh runtime requires Java 17 or newer. The release gate covers Java 17, Java 21, and the current Java 25 LTS smoke lane.

The production Maven support line is Maven 3.9.x. The release matrix pins the current GA Maven 3.9.16 for an exact-version gate in addition to the GitHub runner Maven used by the broader workflows.

Maven 4 is not currently a GA production support claim because Apache Maven 4.0.0-rc-6 is still a release candidate. The pinned RC is nevertheless a blocking compatibility gate on Java 17, 21, and 25. It builds the complete reactor and proves representative JUnit, Cucumber, TestNG, Failsafe, hostile-classpath, and native pass-through behavior. Once Maven 4 reaches GA, ScenarioMesh will pin and qualify that exact GA release before changing the production support claim.

## Versioning

ScenarioMesh uses semantic-version-style product versions:

- patch: compatible bug fixes/security fixes with no intended public configuration/protocol contract break
- minor: additive adapters, reporters, configuration, diagnostics, or compatible protocol capabilities
- major: intentional incompatible configuration, extension, SPI, or distributed-protocol changes

A snapshot build is not a production release.

## Distributed protocol

Coordinator and worker protocol compatibility is independently versioned from the product version. The current session protocol is v9, the minimum negotiated session is v8, and HELLO uses the v8 bootstrap format. Supported v9 and preserved bridge-v8 binaries are exercised in both directions by cross-version fixtures. Non-overlapping ranges and unsupported versions fail closed during registration.

This bridge is a deliberately bounded rolling-upgrade contract, not a claim that arbitrary historical strict-v8 binaries are compatible. Coordinator and worker versions outside the tested bridge window must be upgraded together.

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

The pinned Maven 4 RC lane is blocking to prevent compatibility regressions, but it remains a preview claim until upstream Maven 4 is GA. A newer RC or GA version is not considered supported merely because an older pinned RC passed.
