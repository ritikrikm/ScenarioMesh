package io.scenariomesh.observability.opentelemetry;

import io.scenariomesh.coordinator.RunEvent;
import io.scenariomesh.coordinator.RunEventSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryRunEventSinkTest {
    @Test
    void publishesSafelyWithDefaultNoopApiWhenNoSdkIsInstalled() {
        OpenTelemetryRunEventSink sink = new OpenTelemetryRunEventSink();
        RunEvent event = new RunEvent(Instant.now(), "run-high-cardinality", "TASK_COMPLETED",
                "worker-high-cardinality", "host", "task-high-cardinality", "scope", "work", "lease",
                1, "junit-platform", 2, 1, 25L, "message must not become a metric attribute",
                Map.of("secret", "must-not-become-a-metric-attribute"));

        assertEquals("opentelemetry", sink.id());
        assertDoesNotThrow(() -> sink.publish(event));
        assertDoesNotThrow(() -> sink.publish(null));
    }

    @Test
    void isDiscoverableThroughRunEventSinkServiceLoader() {
        assertTrue(ServiceLoader.load(RunEventSink.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(provider -> provider instanceof OpenTelemetryRunEventSink));
    }
}
