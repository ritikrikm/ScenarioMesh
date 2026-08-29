# OpenTelemetry

ScenarioMesh core does not require OpenTelemetry. An optional module bridges structured runtime events to OpenTelemetry metrics:

```text
io.scenariomesh:scenariomesh-observability-opentelemetry
```

The bridge is discovered through the existing `RunEventSink` ServiceLoader SPI and depends only on `opentelemetry-api`. The application or CI environment owns SDK/exporter installation and configuration. If no SDK is configured, the OpenTelemetry API remains a no-op and ScenarioMesh execution semantics are unchanged.

The bridge records low-cardinality metrics:

```text
scenariomesh.runtime.events
scenariomesh.runtime.event.duration
```

Metric attributes are limited to ScenarioMesh event type and adapter when present. Run IDs, task IDs, worker IDs, work/lease IDs, messages, and arbitrary event attributes are deliberately not promoted to metric labels. This avoids unbounded cardinality and reduces the chance of sensitive target-project data entering telemetry dimensions.

Users that need richer traces/log correlation can implement their own `RunEventSink` using the structured `RunEvent` fields and their organization-specific telemetry policy without changing ScenarioMesh core.
