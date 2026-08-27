package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

import java.util.List;

public final class Protocol {
    /** Version 5 adds runtime-materialized tasks for parameterized/dynamic engines. */
    public static final int VERSION = 5;

    private Protocol() {}

    public enum Type { HELLO, RUN, RESULT, STOP, ACK, ERROR }

    public record WorkerTelemetry(long usedHeapBytes, long maxHeapBytes) {
        public int heapUsagePercent() {
            if (maxHeapBytes <= 0) return 0;
            long percent = (usedHeapBytes * 100L) / maxHeapBytes;
            return (int) Math.max(0L, Math.min(100L, percent));
        }
    }

    public record Envelope(
            int protocolVersion,
            Type type,
            String workerId,
            String token,
            List<ScenarioTask> tasks,
            List<ScenarioTask> materializedTasks,
            Integer attempt,
            List<ExecutionResult> results,
            WorkerTelemetry telemetry,
            String error) {

        public Envelope {
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
            materializedTasks = List.copyOf(materializedTasks == null ? List.of() : materializedTasks);
            results = List.copyOf(results == null ? List.of() : results);
        }

        public static Envelope hello(String workerId, String token) {
            return new Envelope(VERSION, Type.HELLO, workerId, token, List.of(), List.of(), null, List.of(), null, null);
        }

        public static Envelope run(String workerId, ScenarioTask task, int attempt) {
            return runBatch(workerId, List.of(task), attempt);
        }

        public static Envelope runBatch(String workerId, List<ScenarioTask> tasks, int attempt) {
            if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("RUN requires at least one task");
            return new Envelope(VERSION, Type.RUN, workerId, null, tasks, List.of(), attempt, List.of(), null, null);
        }

        public static Envelope result(String workerId, ExecutionResult result, WorkerTelemetry telemetry) {
            return resultBatch(workerId, List.of(), List.of(result), telemetry);
        }

        public static Envelope resultBatch(String workerId, List<ExecutionResult> results, WorkerTelemetry telemetry) {
            return resultBatch(workerId, List.of(), results, telemetry);
        }

        public static Envelope resultBatch(String workerId, List<ScenarioTask> materializedTasks,
                                           List<ExecutionResult> results, WorkerTelemetry telemetry) {
            if (results == null || results.isEmpty()) throw new IllegalArgumentException("RESULT requires at least one terminal result");
            int attempt = results.get(0).attempt();
            return new Envelope(VERSION, Type.RESULT, workerId, null, List.of(), materializedTasks,
                    attempt, results, telemetry, null);
        }

        public ExecutionResult result() { return results.size() == 1 ? results.get(0) : null; }
        public ScenarioTask task() { return tasks.size() == 1 ? tasks.get(0) : null; }

        public static Envelope stop(String workerId) {
            return new Envelope(VERSION, Type.STOP, workerId, null, List.of(), List.of(), null, List.of(), null, null);
        }

        public static Envelope ack(String workerId) {
            return new Envelope(VERSION, Type.ACK, workerId, null, List.of(), List.of(), null, List.of(), null, null);
        }

        public static Envelope error(String workerId, String error) {
            return new Envelope(VERSION, Type.ERROR, workerId, null, List.of(), List.of(), null, List.of(), null, error);
        }
    }
}
