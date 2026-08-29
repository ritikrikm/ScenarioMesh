package io.scenariomesh.observability.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.scenariomesh.coordinator.RunEvent;
import io.scenariomesh.coordinator.RunEventSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryRunEventSinkTest {
    @Test
    void publishesSafelyWithDefaultNoopApiWhenNoSdkIsInstalled() {
        OpenTelemetryRunEventSink sink = new OpenTelemetryRunEventSink();
        RunEvent event = event();

        assertEquals("opentelemetry", sink.id());
        assertDoesNotThrow(() -> sink.publish(event));
        assertDoesNotThrow(() -> sink.publish(null));
    }

    @Test
    void metricAttributesStayLowCardinalityAndNeverPromoteIdsMessagesOrSecrets() {
        var attributes = OpenTelemetryRunEventSink.attributes(event());

        assertEquals("TASK_COMPLETED", attributes.get(AttributeKey.stringKey("scenariomesh.event.type")));
        assertEquals("junit-platform", attributes.get(AttributeKey.stringKey("scenariomesh.adapter")));
        assertNull(attributes.get(AttributeKey.stringKey("scenariomesh.worker.id")));
        assertNull(attributes.get(AttributeKey.stringKey("scenariomesh.task.id")));
        assertNull(attributes.get(AttributeKey.stringKey("secret")));
        assertNull(attributes.get(AttributeKey.stringKey("message")));
    }

    @Test
    void isDiscoverableThroughRunEventSinkServiceLoader() {
        assertTrue(ServiceLoader.load(RunEventSink.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(provider -> provider instanceof OpenTelemetryRunEventSink));
    }

    private RunEvent event() {
        return new RunEvent(Instant.now(), "run-high-cardinality", "TASK_COMPLETED",
                "worker-high-cardinality", "host", "task-high-cardinality", "scope", "work", "lease",
                1, "junit-platform", 2, 1, 25L, "message must not become a metric attribute",
                Map.of("secret", "must-not-become-a-metric-attribute"));
    }
}
