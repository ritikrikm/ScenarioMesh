package io.scenariomesh.observability.opentelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.scenariomesh.coordinator.RunEvent;
import io.scenariomesh.coordinator.RunEventSink;

/**
 * Optional low-cardinality OpenTelemetry metrics bridge for ScenarioMesh runtime events.
 *
 * <p>This module depends only on the OpenTelemetry API. Applications/CI environments remain
 * responsible for installing and configuring an SDK/exporter. With no SDK configured, the
 * OpenTelemetry API is a no-op and ScenarioMesh execution semantics are unchanged.</p>
 */
public final class OpenTelemetryRunEventSink implements RunEventSink {
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("scenariomesh.event.type");
    private static final AttributeKey<String> ADAPTER = AttributeKey.stringKey("scenariomesh.adapter");

    private final LongCounter eventCounter;
    private final LongHistogram durationHistogram;

    public OpenTelemetryRunEventSink() {
        this(GlobalOpenTelemetry.get().getMeter("io.scenariomesh.runtime"));
    }

    OpenTelemetryRunEventSink(Meter meter) {
        eventCounter = meter.counterBuilder("scenariomesh.runtime.events")
                .setDescription("ScenarioMesh structured runtime events")
                .setUnit("{event}")
                .build();
        durationHistogram = meter.histogramBuilder("scenariomesh.runtime.event.duration")
                .ofLongs()
                .setDescription("Duration attached to ScenarioMesh runtime events")
                .setUnit("ms")
                .build();
    }

    @Override
    public String id() {
        return "opentelemetry";
    }

    @Override
    public void publish(RunEvent event) {
        if (event == null) return;
        Attributes attributes = attributes(event);
        eventCounter.add(1L, attributes);
        if (event.durationMillis() != null && event.durationMillis() >= 0L) {
            durationHistogram.record(event.durationMillis(), attributes);
        }
    }

    static Attributes attributes(RunEvent event) {
        var builder = Attributes.builder();
        if (event.type() != null && !event.type().isBlank()) builder.put(EVENT_TYPE, event.type());
        if (event.adapter() != null && !event.adapter().isBlank()) builder.put(ADAPTER, event.adapter());
        return builder.build();
    }
}
