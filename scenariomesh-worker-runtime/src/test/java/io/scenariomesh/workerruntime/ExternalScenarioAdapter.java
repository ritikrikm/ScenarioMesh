package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.util.List;

public final class ExternalScenarioAdapter implements ScenarioAdapter {
    @Override public String id() { return "external-fixture"; }
    @Override public String framework() { return "external-fixture-framework"; }
    @Override public boolean isAvailable(ClassLoader classLoader) { return true; }
    @Override public List<ScenarioTask> discover(AdapterContext context) { return List.of(); }
    @Override public ExecutionResult execute(ScenarioTask task, ExecutionContext context) {
        throw new UnsupportedOperationException("fixture has no executable tasks");
    }
}
