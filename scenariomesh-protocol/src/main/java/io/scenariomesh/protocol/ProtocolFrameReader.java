package io.scenariomesh.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Objects;

/**
 * Reads newline-delimited UTF-8 protocol frames with an explicit byte ceiling.
 *
 * <p>The reader keeps the non-blocking "available" poll behavior used by idle worker refresh
 * loops, but it will immediately reject any frame that grows beyond the configured size cap.
 */
public final class ProtocolFrameReader implements AutoCloseable {
    private final PushbackInputStream input;
    private final int maxFrameBytes;
    private final byte[] frame;

    public ProtocolFrameReader(InputStream input) {
        this(input, Protocol.MAX_PROTOCOL_FRAME_BYTES);
    }

    public ProtocolFrameReader(InputStream input, int maxFrameBytes) {
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.input = new PushbackInputStream(Objects.requireNonNull(input, "input"), maxFrameBytes + 1);
        this.maxFrameBytes = maxFrameBytes;
        this.frame = new byte[maxFrameBytes];
    }

    public byte[] readBlocking() throws IOException {
        return readFrame(true);
    }

    public byte[] readAvailable() throws IOException {
        return readFrame(false);
    }

    private byte[] readFrame(boolean blocking) throws IOException {
        int size = 0;
        while (true) {
            if (!blocking && input.available() == 0) {
                if (size == 0) return null;
                input.unread(frame, 0, size);
                return null;
            }
            int next = input.read();
            if (next == -1) {
                if (size == 0) return null;
                throw new IOException("protocol frame ended before newline delimiter");
            }
            if (next == '\n') {
                return copy(size);
            }
            if (next == '\r') {
                if (!blocking && input.available() == 0) {
                    input.unread(frame, 0, size);
                    input.unread(next);
                    return null;
                }
                int maybeLf = input.read();
                if (maybeLf == '\n' || maybeLf == -1) {
                    return copy(size);
                }
                input.unread(maybeLf);
                return copy(size);
            }
            if (size >= maxFrameBytes) {
                throw new IOException("protocol frame exceeded maximum size of " + maxFrameBytes + " bytes");
            }
            frame[size++] = (byte) next;
        }
    }

    private byte[] copy(int size) {
        byte[] frameBytes = new byte[size];
        System.arraycopy(frame, 0, frameBytes, 0, size);
        return frameBytes;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
