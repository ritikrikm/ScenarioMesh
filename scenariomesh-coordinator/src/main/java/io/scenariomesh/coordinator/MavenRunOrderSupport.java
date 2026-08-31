package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.core.TaskMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Applies Maven Surefire/Failsafe class-level run order before parallel dispatch. */
final class MavenRunOrderSupport {
    private MavenRunOrderSupport() {}

    static boolean active(RunRequest request) {
        return request.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_RUN_ORDER);
    }

    static List<ScenarioTask> order(RunRequest request, List<ScenarioTask> tasks) {
        if (!active(request) || tasks.size() < 2) return List.copyOf(tasks);
        String rawMode = request.executorSystemProperties().get(RuntimePropertyNames.MAVEN_RUN_ORDER);
        String mode = rawMode == null || rawMode.isBlank()
                ? "filesystem" : rawMode.trim().toLowerCase(Locale.ROOT);
        if ("filesystem".equals(mode)) return List.copyOf(tasks);

        LinkedHashMap<String, Bucket> byClass = new LinkedHashMap<>();
        for (ScenarioTask task : tasks) {
            String className = trim(task.metadata().get("className"));
            String scopeId = trim(task.metadata().get(TaskMetadata.EXECUTION_SCOPE_ID));
            String key = className != null ? "class:" + className
                    : scopeId != null ? "scope:" + scopeId
                    : task.source() != null ? "source:" + task.source()
                    : "task:" + task.id().value();
            Bucket bucket = byClass.get(key);
            if (bucket == null) {
                bucket = new Bucket(className, key);
                byClass.put(key, bucket);
            }
            bucket.tasks.add(task);
        }

        List<Bucket> buckets = new ArrayList<>(byClass.values());
        switch (mode) {
            case "alphabetical" -> {
                requireComparableClassNames(buckets, mode);
                buckets.sort(Comparator.comparing(Bucket::className));
            }
            case "reversealphabetical" -> {
                requireComparableClassNames(buckets, mode);
                buckets.sort(Comparator.comparing(Bucket::className).reversed());
            }
            case "random" -> {
                String rawSeed = request.executorSystemProperties().get(RuntimePropertyNames.MAVEN_RUN_ORDER_RANDOM_SEED);
                if (rawSeed == null || rawSeed.isBlank()) {
                    throw new IllegalStateException("Maven random run order is active without an effective random seed");
                }
                final long seed;
                try { seed = Long.parseLong(rawSeed.trim()); }
                catch (NumberFormatException invalid) {
                    throw new IllegalStateException("Invalid Maven random run-order seed: " + rawSeed, invalid);
                }
                // Matches Surefire DefaultRunOrderCalculator: Collections.shuffle(list, new Random(seed)).
                Collections.shuffle(buckets, new Random(seed));
            }
            case "failedfirst", "balanced" -> throw new IllegalStateException(
                    "Stateful Maven run order '" + mode + "' reached the coordinator without an exact statistics-file capability");
            default -> throw new IllegalStateException("Unsupported Maven run order reached coordinator: " + mode);
        }

        List<ScenarioTask> ordered = new ArrayList<>(tasks.size());
        for (Bucket bucket : buckets) ordered.addAll(bucket.tasks);
        return List.copyOf(ordered);
    }

    private static void requireComparableClassNames(List<Bucket> buckets, String mode) {
        long unnamed = buckets.stream().filter(bucket -> bucket.className == null).count();
        if (unnamed > 1) {
            throw new IllegalStateException("Maven runOrder=" + mode
                    + " requires class-level identities, but ScenarioMesh discovery produced " + unnamed
                    + " independently schedulable scopes without class names");
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class Bucket {
        private final String className;
        private final String key;
        private final List<ScenarioTask> tasks = new ArrayList<>();

        private Bucket(String className, String key) {
            this.className = className;
            this.key = key;
        }

        String className() { return className == null ? key : className; }
    }
}
