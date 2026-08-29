# scenariomesh-observability-opentelemetry

Optional OpenTelemetry bridge for ScenarioMesh runtime events.

ScenarioMesh core emits structured, framework-neutral runtime events through its observability SPI. This module maps a deliberately bounded subset of those events to OpenTelemetry metrics.

## Why this is separate

OpenTelemetry is not required for ScenarioMesh execution. Keeping the bridge optional prevents telemetry SDK/exporter choices from becoming a hard dependency of the coordinator or worker runtime.

## Data policy

Metric labels are intentionally low-cardinality. Arbitrary task IDs, worker IDs, run IDs, messages, target-project values, secrets, and uncontrolled attributes must not automatically become telemetry dimensions.

Organizations that need richer tracing/log correlation can implement the event sink SPI under their own security and cardinality policy without changing core execution.
