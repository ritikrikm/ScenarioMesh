package io.scenariomesh.coordinator.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;
import io.scenariomesh.workerruntime.JsonCodec;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteWorkerHandshakeBufferTest {
    @Test
    void legacyPresenceBufferedWithHelloSurvivesRegistrationHandoff() throws Exception {
        ObjectMapper mapper = JsonCodec.create();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(Duration.ofSeconds(30));
        String token = "handshake-buffer-token";

        try (RemoteWorkerServer server = new RemoteWorkerServer(
                     loopback, 0, token, new WorkerRegistrationValidator(), directory);
             Socket client = new Socket(loopback, server.address().getPort());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     client.getOutputStream(), StandardCharsets.UTF_8))) {

            WorkerCapabilities legacyCapabilities = new WorkerCapabilities(
                    "legacy-agent", 1, 17, "Linux", "amd64", "legacy-fp",
                    Set.of("testng"), Set.of());
            Envelope hello = Envelope.hello("legacy-worker", token, legacyCapabilities);
            Envelope presence = Envelope.presence("legacy-worker", null)
                    .withProtocolVersion(Protocol.BOOTSTRAP_VERSION);

            // One flush intentionally allows BufferedReader to read both lines into its buffer.
            writer.write(mapper.writeValueAsString(hello));
            writer.newLine();
            writer.write(mapper.writeValueAsString(presence));
            writer.newLine();
            writer.flush();

            try (RemoteWorkerSession session = server.accept(Duration.ofSeconds(2))) {
                assertEquals(Protocol.BOOTSTRAP_VERSION, session.protocolVersion());
                Envelope bufferedPresence = session.read(Duration.ofSeconds(1));
                assertEquals(Protocol.Type.PRESENCE, bufferedPresence.type());
                assertEquals("legacy-worker", bufferedPresence.workerId());
            }
        }
    }
}
