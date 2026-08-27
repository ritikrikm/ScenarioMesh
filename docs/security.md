# ScenarioMesh security model

ScenarioMesh follows a fail-closed ownership model. It may replace native Maven test execution only after the selected runtime and, in remote mode, the participating worker set are proven compatible. If proof fails, native Maven remains authoritative.

## Distributed transport

Remote coordination bound outside loopback requires TLS. Plain remote TCP is rejected by configuration validation. Loopback-only plaintext remains available for local development and CI fixtures where the coordinator and worker share the host.

TLS uses the JDK JSSE implementation. The coordinator and worker support TLS 1.3 and TLS 1.2. Worker clients enable endpoint identification so the coordinator certificate must match the host name used by the worker. Mutual TLS is the default: `distributed.tls.requireClientAuth` defaults to `true`.

Required remote TLS configuration:

```yaml
scenariomesh:
  configVersion: 1
  workers:
    mode: remote
  distributed:
    bindHost: 0.0.0.0
    bindPort: 43117
    token: ${DO_NOT_COMMIT_A_TOKEN_HERE}
    tls:
      enabled: true
      requireClientAuth: true
      keyStore: /run/secrets/scenariomesh/coordinator.p12
      keyStorePassword: ${DO_NOT_COMMIT_A_PASSWORD_HERE}
      trustStore: /run/secrets/scenariomesh/coordinator-trust.p12
      trustStorePassword: ${DO_NOT_COMMIT_A_PASSWORD_HERE}
```

Do not commit authentication tokens, key-store passwords, private keys, or trust material to a repository. Prefer environment-bound credentials. ScenarioMesh supports the equivalent `SCENARIOMESH_DISTRIBUTED_*` environment variables. The `worker` Mojo passes authentication/TLS material to its child JVM through the process environment and deliberately omits secret-looking Maven system properties from child JVM arguments.

## Authorization and leases

TLS authenticates the transport endpoint. The distributed token authenticates ScenarioMesh registration at the protocol layer. Registration is not work authority.

Every dispatched work unit receives an authoritative `workUnitId` and `leaseId`. Results are accepted only for the current lease. Late, duplicate, stale, or replaced-lease results are rejected. Lease heartbeats can renew only their exact lease.

Idle `PRESENCE` heartbeats are intentionally authority-free. They prove worker/socket liveness but cannot create or renew a work lease.

## Capability authorization

Remote workers explicitly advertise executable adapters and JUnit Platform engine IDs. Transparent takeover is allowed only when the prepared worker set collectively proves coverage for every selected adapter and engine. JUnit engine coverage is valid only when the same worker advertises both `junit-platform` and the required engine.

At runtime, each worker pulls only tasks compatible with its own adapter/engine registration, and ScenarioMesh re-validates that capability immediately before issuing the work lease. Scoped work units include the engine identity in their grouping key so lifecycle affinity cannot merge work from different JUnit engines.

If any discovered task has no eligible registered worker, ScenarioMesh fails closed instead of guessing compatibility.

## Logging, diagnostics, and telemetry

ScenarioMesh never intentionally logs the distributed token or TLS passwords. Structured events pass through a central sanitizer that redacts known configured/environment secret values and truncates oversized messages. Optional observability providers run behind the `RunEventSink` SPI; provider failures do not change the test result.

The `scenariomesh diagnostics` command creates an allowlist-only archive from generated ScenarioMesh reports/events plus a sanitized manifest. It does not dump the process environment and does not collect raw worker logs by default.

Do not rely on masking as the primary secret boundary. Keep credentials out of command arguments and repository files in the first place.

## Worker lifecycle

Healthy remote workers shut down through `DRAIN -> ACK -> STOP -> ACK`. Once draining begins, no new work may be accepted. Workers that fail protocol/result validation are retired immediately rather than gracefully reused.

Transparent Maven takeover retains the exact remote sessions proven during preflight. After native Maven has been suppressed, ScenarioMesh does not silently replace a prepared worker with an unproven connection.

## Protocol compatibility boundary

Protocol v8 remains an exact-version contract. Mixed protocol versions fail closed. The v8 wire format predates an explicit negotiation extension point and registration acknowledgement, so ScenarioMesh does not encode upgrade metadata into unrelated engine IDs, runtime fingerprints, or other capability fields. A future protocol major must introduce negotiation as a first-class wire concept before rolling cross-version operation can be claimed.
