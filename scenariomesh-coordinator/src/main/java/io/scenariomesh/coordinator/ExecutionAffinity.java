package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ScenarioTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds lifecycle-scoped tasks to one worker JVM.
 *
 * <p>The adapter may execute a class/suite/engine scope once and cache the
 * individual leaf outcomes. Keeping every leaf carrying the same scope id on
 * the same worker ensures subsequent leaf requests observe that same lifecycle
 * execution and cache instead of executing the scope again in another JVM.</p>
 */
final class ExecutionAffinity {
    static final String SCOPE_ID = "executionScopeId";

    private final Map<String, String> workerByScope = new ConcurrentHashMap<>();

    boolean eligibleAndClaim(ScenarioTask task, String workerId) {
        String scopeId = scopeId(task);
        if (scopeId == null) {
            return true;
        }
        String owner = workerByScope.putIfAbsent(scopeId, workerId);
        return owner == null || owner.equals(workerId);
    }

    void releaseWorker(String workerId) {
        workerByScope.entrySet().removeIf(entry -> entry.getValue().equals(workerId));
    }

    String ownerOf(String scopeId) {
        return workerByScope.get(scopeId);
    }

    private String scopeId(ScenarioTask task) {
        String value = task.metadata().get(SCOPE_ID);
        return value == null || value.isBlank() ? null : value;
    }
}
