package io.scenariomesh.coordinator;

/**
 * Optional observability SPI. Providers can bridge ScenarioMesh events to OpenTelemetry,
 * a log collector, or an internal metrics system without adding a mandatory runtime dependency.
 */
public interface RunEventSink {
    String id();
    void publish(RunEvent event) throws Exception;
}
