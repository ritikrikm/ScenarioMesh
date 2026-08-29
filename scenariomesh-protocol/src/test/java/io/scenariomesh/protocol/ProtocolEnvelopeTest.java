package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEnvelopeTest {
    @Test
    void redactsAuthenticationTokenFromDiagnostics() {
        Protocol.Envelope hello = Protocol.Envelope.hello("worker-1", "super-secret");
        String text = hello.toString();
        assertFalse(text.contains("super-secret"));
        assertTrue(text.contains("<redacted>"));
    }

    @Test
    void rejectsContradictoryRunPayloadShape() {
        Protocol.Envelope invalid = new Protocol.Envelope(
                Protocol.VERSION,
                Protocol.Type.RUN,
                "worker-1",
                null,
                null,
                null,
                null,
                null,
                List.of(task()),
                List.of(),
                1,
                List.of(),
                null,
                "unexpected error");

        assertThrows(IllegalArgumentException.class, invalid::validatePayloadShape);
    }

    @Test
    void acceptsLegacyRunWithoutLeaseIdentifiers() {
        Protocol.Envelope run = Protocol.Envelope.runBatch("worker-1", List.of(task()), 1);
        run.validatePayloadShape();
    }

    private ScenarioTask task() {
        return new ScenarioTask(
                new ScenarioId("scenario"),
                "scenario",
                "adapter",
                "framework",
                null,
                null,
                "selector",
                Set.of(),
                Map.of());
    }
}
