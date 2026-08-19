package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

public final class Protocol {
    public static final int VERSION = 1;
    private Protocol() {}
    public enum Type { HELLO, RUN, RESULT, STOP, ACK, ERROR }
    public record Envelope(int protocolVersion, Type type, String workerId, String token, ScenarioTask task, ExecutionResult result, String error) {
        public static Envelope hello(String workerId, String token) { return new Envelope(VERSION, Type.HELLO, workerId, token, null, null, null); }
        public static Envelope run(String workerId, ScenarioTask task) { return new Envelope(VERSION, Type.RUN, workerId, null, task, null, null); }
        public static Envelope result(String workerId, ExecutionResult result) { return new Envelope(VERSION, Type.RESULT, workerId, null, null, result, null); }
        public static Envelope stop(String workerId) { return new Envelope(VERSION, Type.STOP, workerId, null, null, null, null); }
        public static Envelope ack(String workerId) { return new Envelope(VERSION, Type.ACK, workerId, null, null, null, null); }
        public static Envelope error(String workerId, String error) { return new Envelope(VERSION, Type.ERROR, workerId, null, null, null, error); }
    }
}
