package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

public final class Protocol {
    public static final int VERSION = 2;

    private Protocol() {}

    public enum Type { HELLO, RUN, RESULT, STOP, ACK, ERROR }

    /** Post-task worker health snapshot. Values are bytes; -1 max means unknown/unbounded. */
    public record WorkerTelemetry(long usedHeapBytes, long maxHeapBytes) {
        public int heapUsagePercent() {
            if (maxHeapBytes <= 0) {
                return 0;
            }
            long percent = (usedHeapBytes * 100L) / maxHeapBytes;
            return (int) Math.max(0L, Math.min(100L, percent));
        }
    }

    public record Envelope(
            int protocolVersion,
            Type type,
            String workerId,
            String token,
            ScenarioTask task,
            Integer attempt,
            ExecutionResult result,
            WorkerTelemetry telemetry,
            String error) {

        public static Envelope hello(String workerId, String token) {
            return new Envelope(VERSION, Type.HELLO, workerId, token, null, null, null, null, null);
        }

        public static Envelope run(String workerId, ScenarioTask task, int attempt) {
            return new Envelope(VERSION, Type.RUN, workerId, null, task, attempt, null, null, null);
        }

        public static Envelope result(String workerId, ExecutionResult result, WorkerTelemetry telemetry) {
            return new Envelope(VERSION, Type.RESULT, workerId, null, null, result.attempt(), result, telemetry, null);
        }

        public static Envelope stop(String workerId) {
            return new Envelope(VERSION, Type.STOP, workerId, null, null, null, null, null, null);
        }

        public static Envelope ack(String workerId) {
            return new Envelope(VERSION, Type.ACK, workerId, null, null, null, null, null, null);
        }

        public static Envelope error(String workerId, String error) {
            return new Envelope(VERSION, Type.ERROR, workerId, null, null, null, null, null, error);
        }
    }
}
