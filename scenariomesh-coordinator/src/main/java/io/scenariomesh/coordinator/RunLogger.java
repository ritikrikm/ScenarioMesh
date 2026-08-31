package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/** Centralizes human, machine-readable and optional external runtime logging. */
final class RunLogger {
    private static final int MAX_MESSAGE_CHARS = 16_384;
    private final ScenarioMeshConfig config;
    private final String runId;
    private final Path eventsFile;
    private final List<RunEventSink> sinks;
    private final List<String> secrets;
    private final ObjectMapper mapper = JsonCodec.create();

    RunLogger(ScenarioMeshConfig config) { this(config, null, null); }

    RunLogger(ScenarioMeshConfig config, String runId, Path runDirectory) {
        this.config = Objects.requireNonNull(config, "config");
        this.runId = runId;
        this.eventsFile = runDirectory == null ? null : runDirectory.resolve("events.jsonl");
        this.secrets = collectSecrets(config);
        this.sinks = loadSinks();
    }

    synchronized void info(String message) {
        String safe = sanitize(message);
        System.out.println("[ScenarioMesh] " + safe);
        event(new RunEvent(Instant.now(), runId, "INFO", null, null, safe));
    }

    synchronized void progress(String message) {
        String safe = sanitize(message);
        if (config.showProgress()) System.out.println("[ScenarioMesh] " + safe);
        event(new RunEvent(Instant.now(), runId, "PROGRESS", null, null, safe));
    }

    synchronized void mavenRerunRound(int rerunIndex, int taskCount, int configuredReruns) {
        String message = "Maven rerun round " + rerunIndex + "/" + configuredReruns
                + " executing " + taskCount + " failed logical test(s)";
        if (config.showProgress()) System.out.println("[ScenarioMesh] " + message);
        event(new RunEvent(Instant.now(), runId, "MAVEN_RERUN_ROUND", null, null,
                null, null, null, null, rerunIndex, null, taskCount, null, null,
                message, Map.of("rerunIndex", Integer.toString(rerunIndex),
                        "configuredReruns", Integer.toString(configuredReruns),
                        "logicalTaskCount", Integer.toString(taskCount))));
    }

    synchronized void workerOutput(String workerId, String line) {
        String safe = sanitize(line);
        if (config.liveConsoleLogs()) System.out.println("[ScenarioMesh][" + workerId + "] " + safe);
        event(new RunEvent(Instant.now(), runId, "WORKER_OUTPUT", workerId, null, safe));
    }

    synchronized void workerCompleted(String workerId, ExecutionResult result,
                                      int completed, int failed, int busy, int total) {
        int queued = Math.max(0, total - completed - busy);
        if (config.showProgress()) {
            System.out.println("[ScenarioMesh] " + workerId + " " + result.status().name() + " " + sanitize(result.displayName())
                    + " | completed=" + completed + "/" + total + " failed=" + failed
                    + " busy=" + busy + " queued=" + queued);
        }
        event(new RunEvent(Instant.now(), runId, "TASK_COMPLETED", workerId, null,
                result.scenarioId().value(), null, null, null, result.attempt(), null,
                queued, busy, result.duration().toMillis(),
                "status=" + result.status().name(), Map.of()));
    }

    synchronized void schedulerDecision(String workerId, ScenarioTask task, Envelope lease,
                                        int queueDepth, String reason) {
        event(new RunEvent(Instant.now(), runId, "SCHEDULER_DECISION", workerId, null,
                task.id().value(), task.metadata().get("executionScopeId"),
                lease == null ? null : lease.workUnitId(), lease == null ? null : lease.leaseId(),
                lease == null ? null : lease.attempt(), task.adapterId(), queueDepth, null, null,
                sanitize(reason), Map.of("framework", safeAttribute(task.framework()))));
    }

    synchronized void workerLifecycle(String type, String workerId, String hostId, String message) {
        event(new RunEvent(Instant.now(), runId, type, workerId, hostId,
                null, null, null, null, null, null, null, null, null,
                sanitize(message), Map.of()));
    }

    synchronized void leaseHeartbeat(String workerId, String workUnitId, String leaseId) {
        event(new RunEvent(Instant.now(), runId, "LEASE_HEARTBEAT", workerId, null,
                null, null, workUnitId, leaseId, null, null, null, null, null,
                "authoritative heartbeat", Map.of()));
    }

    synchronized void compatibilityDecision(boolean owned, String reason) {
        event(new RunEvent(Instant.now(), runId, owned ? "COMPATIBILITY_OWNED" : "COMPATIBILITY_PASS_THROUGH",
                null, null, null, null, null, null, null, null, null, null, null,
                sanitize(reason), Map.of()));
    }

    private void event(RunEvent raw) {
        RunEvent event = sanitize(raw);
        if (eventsFile != null) {
            try {
                String json = mapper.writeValueAsString(event) + System.lineSeparator();
                Files.writeString(eventsFile, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception exception) {
                diagnostic("Failed to append structured event log: " + exception.getMessage());
            }
        }
        for (RunEventSink sink : sinks) {
            try { sink.publish(event); }
            catch (Exception exception) {
                diagnostic("Observability sink '" + safeId(sink) + "' failed: " + exception.getMessage());
            }
        }
    }

    private RunEvent sanitize(RunEvent event) {
        Map<String, String> safeAttributes = event.attributes().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> safeAttribute(entry.getValue())));
        return new RunEvent(event.timestamp(), event.runId(), event.type(), event.workerId(), event.hostId(),
                event.taskId(), event.scopeId(), event.workUnitId(), event.leaseId(), event.attempt(),
                event.adapter(), event.queueDepth(), event.busyWorkers(), event.durationMillis(),
                sanitize(event.message()), safeAttributes);
    }

    private List<RunEventSink> loadSinks() {
        List<RunEventSink> loaded = new ArrayList<>();
        try {
            ServiceLoader.load(RunEventSink.class, Thread.currentThread().getContextClassLoader()).forEach(loaded::add);
        } catch (ServiceConfigurationError error) {
            diagnostic("Observability sink SPI could not load a provider: " + error.getMessage());
        }
        return List.copyOf(loaded);
    }

    private List<String> collectSecrets(ScenarioMeshConfig config) {
        Set<String> values = new LinkedHashSet<>();
        addSecret(values, config.distributed().token());
        addSecret(values, config.distributed().tls().keyStorePassword());
        addSecret(values, config.distributed().tls().trustStorePassword());
        System.getenv().forEach((key, value) -> {
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("token") || normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("credential")) addSecret(values, value);
        });
        return List.copyOf(values);
    }

    private void addSecret(Set<String> values, String value) {
        if (value != null && !value.isBlank() && value.length() >= 4) values.add(value);
    }

    private String sanitize(String value) {
        if (value == null) return "";
        String safe = value;
        for (String secret : secrets) safe = safe.replace(secret, "***");
        if (safe.length() > MAX_MESSAGE_CHARS) safe = safe.substring(0, MAX_MESSAGE_CHARS) + "...[truncated]";
        return safe;
    }

    private String safeAttribute(String value) { return sanitize(value == null ? "" : value); }

    private String safeId(RunEventSink sink) {
        try {
            String id = sink.id();
            return id == null || id.isBlank() ? sink.getClass().getName() : id;
        } catch (Exception ignored) { return sink.getClass().getName(); }
    }

    private void diagnostic(String message) {
        if (config.showProgress()) System.err.println("[ScenarioMesh] " + sanitize(message));
    }
}
