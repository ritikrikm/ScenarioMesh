package io.scenariomesh.protocol;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class Protocol {
    /**
     * Version 9 adds explicit supported-version range negotiation. HELLO deliberately remains
     * encoded as the v8 bootstrap protocol so bridge-v8 peers can parse and authenticate it.
     */
    public static final int VERSION = 9;
    public static final int MIN_SUPPORTED_VERSION = 8;
    public static final int BOOTSTRAP_VERSION = 8;
    /**
     * Upper bound for a single newline-delimited protocol frame before it is treated as malformed.
     * This keeps a compromised or buggy peer from forcing the coordinator/worker runtime to buffer
     * an unbounded JSON line in memory.
     */
    public static final int MAX_PROTOCOL_FRAME_BYTES = 4 * 1024 * 1024;

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
            Set<String> engineIds,
            Integer minProtocolVersion,
            Integer maxProtocolVersion) {
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
            if ((minProtocolVersion == null) != (maxProtocolVersion == null)) {
                throw new IllegalArgumentException("protocol range requires both minProtocolVersion and maxProtocolVersion");
            }
            if (minProtocolVersion != null
                    && (minProtocolVersion < 1 || maxProtocolVersion < minProtocolVersion)) {
                throw new IllegalArgumentException("invalid supported protocol range");
            }
        }

        /** Legacy constructor retained for v8 fixtures and callers that do not advertise negotiation support. */
        public WorkerCapabilities(String agentId, int slots, int javaFeature, String osName,
                                  String architecture, String runtimeFingerprint,
                                  Set<String> adapterIds, Set<String> engineIds) {
            this(agentId, slots, javaFeature, osName, architecture, runtimeFingerprint,
                    adapterIds, engineIds, null, null);
        }

        public WorkerCapabilities(String agentId, int slots, int javaFeature, String osName,
                                  String architecture, String runtimeFingerprint) {
            this(agentId, slots, javaFeature, osName, architecture, runtimeFingerprint,
                    Set.of(), Set.of(), null, null);
        }

        public boolean advertisesProtocolRange() {
            return minProtocolVersion != null && maxProtocolVersion != null;
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
            return new Envelope(BOOTSTRAP_VERSION, Type.HELLO, workerId, token, capabilities,
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

        /** Re-encodes an otherwise identical envelope for a negotiated session protocol. */
        public Envelope withProtocolVersion(int version) {
            if (version < 1) throw new IllegalArgumentException("protocol version must be positive");
            return new Envelope(version, type, workerId, token, capabilities, workUnitId, leaseId,
                    leaseExpiresAt, tasks, materializedTasks, attempt, results, telemetry, error);
        }

        /**
         * Validates the type-specific wire shape after decoding. The envelope remains tolerant of
         * legacy v8 fields, but contradictory payload families are rejected instead of being left
         * to ad-hoc coordinator/worker checks.
         */
        public void validatePayloadShape() {
            if (type == null) throw new IllegalArgumentException("protocol message type is required");
            if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("protocol workerId is required");
            switch (type) {
                case HELLO -> {
                    requireEmpty("HELLO tasks", tasks);
                    requireEmpty("HELLO materializedTasks", materializedTasks);
                    requireEmpty("HELLO results", results);
                    if (attempt != null || error != null) throw invalid("HELLO contains execution/result fields");
                }
                case RUN -> {
                    if (tasks.isEmpty()) throw invalid("RUN requires at least one task");
                    if (attempt == null || attempt < 1) throw invalid("RUN requires a positive attempt");
                    requireEmpty("RUN materializedTasks", materializedTasks);
                    requireEmpty("RUN results", results);
                    if (error != null) throw invalid("RUN contains an error payload");
                }
                case RESULT -> {
                    requireEmpty("RESULT tasks", tasks);
                    if (results.isEmpty()) throw invalid("RESULT requires at least one terminal result");
                    if (attempt == null || attempt < 1) throw invalid("RESULT requires a positive attempt");
                    if (error != null) throw invalid("RESULT contains an error payload");
                }
                case HEARTBEAT -> {
                    if (workUnitId == null || workUnitId.isBlank() || leaseId == null || leaseId.isBlank()) {
                        throw invalid("HEARTBEAT requires workUnitId and leaseId");
                    }
                    requireEmpty("HEARTBEAT tasks", tasks);
                    requireEmpty("HEARTBEAT materializedTasks", materializedTasks);
                    requireEmpty("HEARTBEAT results", results);
                    if (attempt != null || error != null) throw invalid("HEARTBEAT contains terminal/execution payload");
                }
                case PRESENCE, DRAIN, STOP, ACK -> {
                    requireEmpty(type + " tasks", tasks);
                    requireEmpty(type + " materializedTasks", materializedTasks);
                    requireEmpty(type + " results", results);
                    if (attempt != null || error != null) throw invalid(type + " contains terminal/execution payload");
                    if (type != Type.PRESENCE && telemetry != null) throw invalid(type + " contains telemetry");
                }
                case ERROR -> {
                    requireEmpty("ERROR tasks", tasks);
                    requireEmpty("ERROR materializedTasks", materializedTasks);
                    requireEmpty("ERROR results", results);
                    if (error == null || error.isBlank()) throw invalid("ERROR requires a message");
                    if (attempt != null) throw invalid("ERROR contains an execution attempt");
                }
            }
        }

        /** Authentication material must never appear in ordinary diagnostics. */
        @Override
        public String toString() {
            return "Envelope[protocolVersion=" + protocolVersion
                    + ", type=" + type
                    + ", workerId=" + workerId
                    + ", token=" + (token == null || token.isBlank() ? "<unset>" : "<redacted>")
                    + ", capabilities=" + capabilities
                    + ", workUnitId=" + workUnitId
                    + ", leaseId=" + leaseId
                    + ", leaseExpiresAt=" + leaseExpiresAt
                    + ", tasks=" + tasks.size()
                    + ", materializedTasks=" + materializedTasks.size()
                    + ", attempt=" + attempt
                    + ", results=" + results.size()
                    + ", telemetry=" + telemetry
                    + ", error=" + error + "]";
        }

        private void requireEmpty(String name, List<?> values) {
            if (!values.isEmpty()) throw invalid(name + " must be empty");
        }

        private IllegalArgumentException invalid(String detail) {
            return new IllegalArgumentException("Invalid ScenarioMesh " + type + " envelope: " + detail);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
