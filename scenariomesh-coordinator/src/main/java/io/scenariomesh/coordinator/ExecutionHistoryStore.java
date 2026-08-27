package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.workerruntime.JsonCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, bounded persistent duration history keyed by stable task and lifecycle-scope identity. */
final class ExecutionHistoryStore {
    static final String ESTIMATED_DURATION_MILLIS = "estimatedDurationMillis";
    static final int SCHEMA_VERSION = 2;
    static final int MAX_ENTRIES = 20_000;
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";
    private static final String FILE_NAME = "execution-history.json";
    private static final String CORRUPT_FILE_NAME = "execution-history.corrupt.json";
    private static final String SCOPE_PREFIX = "scope:";
    private final ObjectMapper mapper = JsonCodec.create();

    List<ScenarioTask> enrich(Path reportingDirectory, List<ScenarioTask> tasks) {
        Map<String, HistoryEntry> history = load(reportingDirectory);
        if (history.isEmpty()) return tasks;
        List<ScenarioTask> enriched = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            String scope = task.metadata().get(EXECUTION_SCOPE_ID);
            HistoryEntry estimate = scope == null || scope.isBlank()
                    ? history.get(task.id().value()) : history.get(SCOPE_PREFIX + scope);
            if (estimate == null || estimate.estimateMillis() <= 0) estimate = history.get(task.id().value());
            if (estimate == null || estimate.estimateMillis() <= 0) {
                enriched.add(task);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(task.metadata());
            metadata.put(ESTIMATED_DURATION_MILLIS, Long.toString(estimate.estimateMillis()));
            enriched.add(new ScenarioTask(task.id(), task.displayName(), task.adapterId(), task.framework(),
                    task.source(), task.line(), task.selector(), task.tags(), metadata));
        }
        return List.copyOf(enriched);
    }

    void update(Path reportingDirectory, List<ScenarioTask> tasks, List<ExecutionResult> results) {
        try {
            Files.createDirectories(reportingDirectory);
            Map<String, HistoryEntry> history = new LinkedHashMap<>(load(reportingDirectory));
            Map<String, ScenarioTask> taskById = new HashMap<>();
            for (ScenarioTask task : tasks) taskById.put(task.id().value(), task);
            Map<String, Long> observedScopeMillis = new LinkedHashMap<>();
            long observedAt = Instant.now().toEpochMilli();

            for (ExecutionResult result : results) {
                if (!isExecutionSignal(result.status())) continue;
                long observed = Math.max(1L, result.duration().toMillis());
                mergeEstimate(history, result.scenarioId().value(), observed, observedAt);
                ScenarioTask task = taskById.get(result.scenarioId().value());
                if (task != null) {
                    String scope = task.metadata().get(EXECUTION_SCOPE_ID);
                    if (scope != null && !scope.isBlank()) observedScopeMillis.merge(scope, observed, Long::sum);
                }
            }
            observedScopeMillis.forEach((scope, observed) -> mergeEstimate(history, SCOPE_PREFIX + scope, observed, observedAt));
            history = bounded(history);
            persist(reportingDirectory, history);
        } catch (Exception ignored) {
            // Scheduling history is an optimization only and must never change test outcomes.
        }
    }

    private void persist(Path reportingDirectory, Map<String, HistoryEntry> history) throws Exception {
        Path target = reportingDirectory.resolve(FILE_NAME);
        Path temporary = reportingDirectory.resolve(FILE_NAME + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                new HistoryDocument(SCHEMA_VERSION, history));
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void mergeEstimate(Map<String, HistoryEntry> history, String key, long observed, long observedAt) {
        HistoryEntry previous = history.get(key);
        long estimate = previous == null ? observed
                : Math.max(1L, Math.round(previous.estimateMillis() * 0.75d + observed * 0.25d));
        history.put(key, new HistoryEntry(estimate, observedAt));
    }

    private Map<String, HistoryEntry> bounded(Map<String, HistoryEntry> history) {
        if (history.size() <= MAX_ENTRIES) return history;
        return history.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, HistoryEntry>>comparingLong(e -> e.getValue().lastObservedEpochMillis())
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(MAX_ENTRIES)
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);
    }

    private Map<String, HistoryEntry> load(Path reportingDirectory) {
        Path path = reportingDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) return Map.of();
        try {
            JsonNode root = mapper.readTree(path.toFile());
            if (root == null || !root.isObject()) throw new IllegalStateException("history root must be an object");
            if (root.has("version")) {
                int version = root.path("version").asInt(-1);
                if (version != SCHEMA_VERSION) return Map.of();
                HistoryDocument document = mapper.treeToValue(root, HistoryDocument.class);
                return document == null || document.entries() == null ? Map.of() : sanitize(document.entries());
            }
            // One-time migration from the original flat {id: millis} format.
            Map<String, HistoryEntry> migrated = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().canConvertToLong()) {
                    long value = entry.getValue().asLong();
                    if (value > 0) migrated.put(entry.getKey(), new HistoryEntry(value, 0L));
                }
            });
            return Map.copyOf(migrated);
        } catch (Exception corrupt) {
            quarantineCorrupt(path, reportingDirectory.resolve(CORRUPT_FILE_NAME));
            return Map.of();
        }
    }

    private Map<String, HistoryEntry> sanitize(Map<String, HistoryEntry> entries) {
        Map<String, HistoryEntry> clean = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && value.estimateMillis() > 0) clean.put(key, value);
        });
        return Map.copyOf(bounded(clean));
    }

    private void quarantineCorrupt(Path source, Path destination) {
        try { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception ignored) { }
    }

    private boolean isExecutionSignal(ResultStatus status) {
        return status == ResultStatus.PASSED || status == ResultStatus.SKIPPED || status == ResultStatus.TEST_FAILURE;
    }

    record HistoryEntry(long estimateMillis, long lastObservedEpochMillis) {}
    record HistoryDocument(int version, Map<String, HistoryEntry> entries) {}
}
