package io.scenariomesh.workerruntime;

import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonCodecCompatibilityTest {
    @Test
    void additiveFutureEnvelopeFieldsDoNotBreakCurrentDecoder() throws Exception {
        String json = """
                {
                  "protocolVersion": 8,
                  "type": "ACK",
                  "workerId": "worker-a",
                  "futureNegotiation": {"min": 8, "max": 9},
                  "futureFlag": true
                }
                """;

        Envelope envelope = JsonCodec.create().readValue(json, Envelope.class);

        assertEquals(Protocol.VERSION, envelope.protocolVersion());
        assertEquals(Protocol.Type.ACK, envelope.type());
        assertEquals("worker-a", envelope.workerId());
    }
}
