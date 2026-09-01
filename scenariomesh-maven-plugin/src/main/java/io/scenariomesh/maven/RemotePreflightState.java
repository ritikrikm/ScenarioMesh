package io.scenariomesh.maven;

import io.scenariomesh.coordinator.PreparedRemoteWorkers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Maven-plugin-context handoff for authenticated remote sessions between injected Mojo phases. */
final class RemotePreflightState {
    private static final String KEY = RemotePreflightState.class.getName() + ".preparedWorkers";

    private RemotePreflightState() {}

    /** Replaces any prior state with one prepared execution cohort. */
    static void store(Map<String, Object> pluginContext, PreparedRemoteWorkers prepared) {
        storeAll(pluginContext, List.of(prepared));
    }

    /**
     * Atomically replaces prior state with execution-ordered remote cohorts.
     * Each injected RunMojo consumes exactly one cohort with {@link #take(Map)}.
     */
    static void storeAll(Map<String, Object> pluginContext, List<PreparedRemoteWorkers> prepared) {
        clear(pluginContext);
        if (prepared == null || prepared.isEmpty()) return;
        Deque<PreparedRemoteWorkers> queue = new ArrayDeque<>(prepared);
        pluginContext.put(KEY, queue);
    }

    static PreparedRemoteWorkers take(Map<String, Object> pluginContext) {
        Object value = pluginContext.get(KEY);
        if (value == null) return null;
        if (value instanceof PreparedRemoteWorkers legacy) {
            pluginContext.remove(KEY);
            return legacy;
        }
        if (!(value instanceof Deque<?> raw)) {
            throw new IllegalStateException("Unexpected ScenarioMesh remote preflight state: " + value.getClass().getName());
        }
        Object next = raw.pollFirst();
        if (raw.isEmpty()) pluginContext.remove(KEY);
        if (next == null) return null;
        if (!(next instanceof PreparedRemoteWorkers prepared)) {
            throw new IllegalStateException("Unexpected ScenarioMesh remote preflight queue entry: " + next.getClass().getName());
        }
        return prepared;
    }

    static boolean hasRemaining(Map<String, Object> pluginContext) {
        Object value = pluginContext.get(KEY);
        if (value == null) return false;
        if (value instanceof PreparedRemoteWorkers) return true;
        if (value instanceof Deque<?> queue) return !queue.isEmpty();
        throw new IllegalStateException("Unexpected ScenarioMesh remote preflight state: " + value.getClass().getName());
    }

    static void clear(Map<String, Object> pluginContext) {
        Object value = pluginContext.remove(KEY);
        if (value == null) return;
        List<PreparedRemoteWorkers> prepared = new ArrayList<>();
        if (value instanceof PreparedRemoteWorkers one) prepared.add(one);
        else if (value instanceof Deque<?> queue) {
            for (Object item : queue) {
                if (item instanceof PreparedRemoteWorkers workers) prepared.add(workers);
            }
        } else {
            throw new IllegalStateException("Unexpected ScenarioMesh remote preflight state: " + value.getClass().getName());
        }
        prepared.forEach(PreparedRemoteWorkers::close);
    }
}
