package io.scenariomesh.maven;

import io.scenariomesh.coordinator.PreparedRemoteWorkers;

import java.util.Map;

/** Maven-plugin-context handoff for authenticated remote sessions between injected Mojo phases. */
final class RemotePreflightState {
    private static final String KEY = RemotePreflightState.class.getName() + ".preparedWorkers";

    private RemotePreflightState() {}

    static void store(Map<String, Object> pluginContext, PreparedRemoteWorkers prepared) {
        PreparedRemoteWorkers previous = take(pluginContext);
        if (previous != null) previous.close();
        pluginContext.put(KEY, prepared);
    }

    static PreparedRemoteWorkers take(Map<String, Object> pluginContext) {
        Object value = pluginContext.remove(KEY);
        if (value == null) return null;
        if (!(value instanceof PreparedRemoteWorkers prepared)) {
            throw new IllegalStateException("Unexpected ScenarioMesh remote preflight state: " + value.getClass().getName());
        }
        return prepared;
    }

    static void clear(Map<String, Object> pluginContext) {
        PreparedRemoteWorkers prepared = take(pluginContext);
        if (prepared != null) prepared.close();
    }
}
