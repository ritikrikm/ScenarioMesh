# Diagnostics

ScenarioMesh can package generated operational evidence into a bounded diagnostics ZIP:

```bash
java -jar scenariomesh-cli-<version>.jar diagnostics --root .
```

Default output:

```text
target/scenariomesh-diagnostics.zip
```

A different destination can be selected with `--output PATH`.

## Security model

Diagnostics is allowlist-based rather than a recursive workspace archive. It may include:

- a generated `diagnostics/manifest.json`;
- `summary.json`;
- `junit.xml`;
- `report.html`;
- the latest run's `events.jsonl`.

It does not dump process environment variables, does not include configured distributed tokens or TLS passwords in the manifest, and does not collect raw per-worker stdout/stderr logs by default. Each included file is bounded to 20 MiB. This is intentional: a diagnostics command must not become an implicit secret/file exfiltration mechanism.

The manifest records product/runtime facts useful for support such as ScenarioMesh version, protocol version, Java/OS details, adapter intent, scheduling strategy, worker mode/count, TLS state, and report directory.

## Protocol troubleshooting

Protocol v8 is an exact-version contract. Mixed protocol versions fail closed. The diagnostics manifest records the local protocol version so coordinator/worker version mismatches can be identified without exposing authentication material.
