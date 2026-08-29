package io.scenariomesh.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolFrameReaderTest {
    @Test
    void blockingReadAcceptsLfAndCrLf() throws Exception {
        ProtocolFrameReader lfReader = new ProtocolFrameReader(
                new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)), 32);
        ProtocolFrameReader crlfReader = new ProtocolFrameReader(
                new ByteArrayInputStream("world\r\n".getBytes(StandardCharsets.UTF_8)), 32);

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), lfReader.readBlocking());
        assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), crlfReader.readBlocking());
    }

    @Test
    void blockingReadRejectsOversizedFrameBeforeDelimiter() {
        byte[] payload = ("x".repeat(9) + "\n").getBytes(StandardCharsets.UTF_8);
        ProtocolFrameReader reader = new ProtocolFrameReader(new ByteArrayInputStream(payload), 8);

        assertThrows(IOException.class, reader::readBlocking);
    }

    @Test
    void availableReadLeavesPartialFrameBufferedUntilCompletion() throws Exception {
        ScriptedInputStream input = new ScriptedInputStream(
                "hel".getBytes(StandardCharsets.UTF_8),
                "lo\n".getBytes(StandardCharsets.UTF_8));
        ProtocolFrameReader reader = new ProtocolFrameReader(input, 32);

        assertNull(reader.readAvailable());
        input.releaseNextChunk();
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), reader.readAvailable());
    }

    private static final class ScriptedInputStream extends InputStream {
        private final byte[] firstChunk;
        private final byte[] secondChunk;
        private boolean secondReleased;
        private int index;

        private ScriptedInputStream(byte[] firstChunk, byte[] secondChunk) {
            this.firstChunk = firstChunk;
            this.secondChunk = secondChunk;
        }

        void releaseNextChunk() {
            secondReleased = true;
            index = 0;
        }

        @Override
        public int read() {
            byte[] current = secondReleased ? secondChunk : firstChunk;
            if (index >= current.length) return -1;
            return current[index++] & 0xff;
        }

        @Override
        public int available() {
            byte[] current = secondReleased ? secondChunk : firstChunk;
            return Math.max(0, current.length - index);
        }
    }
}
