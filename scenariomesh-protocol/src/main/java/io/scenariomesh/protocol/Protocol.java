package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class Protocol {
    /** Version 8 adds idle worker presence heartbeats and graceful draining. */
    public static final int VERSION = 8;

    private Protocol() {}

    public enum Type { HELLO, RUN, RESULT, HEARTBEAT, PRESENCE, DRAIN, STOP, ACK, ERROR }

    public record WorkerCapabilities(
            String agentId,
            int slots,
            int javaFeature,
            String osName,
            String architecture,
            String runtimeFingerprint,
            Set<String> adapterIds,
            Set<String> engineIds) {
        public WorkerCapabilities {
            agentId = require(agentId, "agentId");
            if (slots < 1) throw new IllegalArgumentException("worker slots must be positive");
            if (javaFeature < 1) throw new IllegalArgumentException("javaFeature must be positive");
            osName = require(osName, "osName");
            architecture = require(architecture, "architecture");
            runtimeFingerprint = require(runtimeFingerprint, "runtimeFingerprint");
            adapterIds = Set.copyOf(adapterIds == null ? Set.of() : adapterIds);
            engineIds = Set.copyOf(engineIds == null ? Set.of() : engineIds);
            if (adapterIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("adapterIds must not contain blank ids");
            }
            if (engineIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("engineIds must not contain blank ids");
            }
        }

        public WorkerCapabilities(String agentId, int slots, int javaFeature, String osName,
                                  String architecture, String runtimeFingerprint) {
            this(agentId, slots, javaFeature, osName, architecture, runtimeFingerprint, Set.of(), Set.of());
        }
    }

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
            WorkerCapabilities capabilities,
            String workUnitId,
            String leaseId,
            Instant leaseExpiresAt,
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

        public static Envelope hello(String workerId, String token) { return hello(workerId, token, null); }
        public static Envelope hello(String workerId, String token, WorkerCapabilities capabilities) {
            return new Envelope(VERSION, Type.HELLO, workerId, token, capabilities,
                    null, null, null, List.of(), List.of(), null, List.of(), null, null);
        }

        public static Envelope run(String workerId, ScenarioTask task, int attempt) {
            return runBatch(workerId, List.of(task), attempt);
        }
        public static Envelope runBatch(String workerId, List<ScenarioTask> tasks, int attempt) {
            return runBatch(workerId, null, null, null, tasks, attempt);
        }
        public static Envelope runBatch(String workerId, String workUnitId, String leaseId, Instant leaseExpiresAt,
                                        List<ScenarioTask> tasks, int attempt) {
            if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("RUN requires at least one task");
            return new Envelope(VERSION, Type.RUN, workerId, null, null,
                    workUnitId, leaseId, leaseExpiresAt, tasks, List.of(), attempt, List.of(), null, null);
        }

        public static Envelope result(String workerId, ExecutionResult result, WorkerTelemetry telemetry) {
            return resultBatch(workerId, List.of(), List.of(result), telemetry);
        }
        public static Envelope resultBatch(String workerId, List<ExecutionResult> results, WorkerTelemetry telemetry) {
            return resultBatch(workerId, List.of(), results, telemetry);
        }
        public static Envelope resultBatch(String workerId, List<ScenarioTask> materializedTasks,
                                           List<ExecutionResult> results, WorkerTelemetry telemetry) {
            return resultBatch(workerId, null, null, materializedTasks, results, telemetry);
        }
        public static Envelope resultBatch(String workerId, String workUnitId, String leaseId,
                                           List<ScenarioTask> materializedTasks,
                                           List<ExecutionResult> results, WorkerTelemetry telemetry) {
            if (results == null || results.isEmpty()) throw new IllegalArgumentException("RESULT requires at least one terminal result");
            int attempt = results.get(0).attempt();
            return new Envelope(VERSION, Type.RESULT, workerId, null, null,
                    workUnitId, leaseId, null, List.of(), materializedTasks,
                    attempt, results, telemetry, null);
        }

        /** Lease-scoped heartbeat. It may renew only the exact authoritative work lease. */
        public static Envelope heartbeat(String workerId, String workUnitId, String leaseId, WorkerTelemetry telemetry) {
            return new Envelope(VERSION, Type.HEARTBEAT, workerId, null, null,
                    workUnitId, leaseId, null, List.of(), List.of(), null, List.of(), telemetry, null);
        }

        /** Presence heartbeat proves the worker process/socket is alive but grants no work authority. */
        public static Envelope presence(String workerId, WorkerTelemetry telemetry) {
            return new Envelope(VERSION, Type.PRESENCE, workerId, null, null,
                    null, null, null, List.of(), List.of(), null, List.of(), telemetry, null);
        }

        public static Envelope drain(String workerId) {
            return new Envelope(VERSION, Type.DRAIN, workerId, null, null,
                    null, null, null, List.of(), List.of(), null, List.of(), null, null);
        }

        public ExecutionResult result() { return results.size() == 1 ? results.get(0) : null; }
        public ScenarioTask task() { return tasks.size() == 1 ? tasks.get(0) : null; }

        public static Envelope stop(String workerId) {
            return new Envelope(VERSION, Type.STOP, workerId, null, null,
                    null, null, null, List.of(), List.of(), null, List.of(), null, null);
        }
        public static Envelope ack(String workerId) {
            return new Envelope(VERSION, Type.ACK, workerId, null, null,
                    null, null, null, List.of(), List.of(), null, List.of(), null, null);
        }
        public static Envelope error(String workerId, String error) {
            return new Envelope(VERSION, Type.ERROR, workerId, null, null,
                    null, null, null, List.of(), List.of(), null, List.of(), null, error);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
