package io.scenariomesh.core;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class Ports {
    private Ports() {}

    public interface ScenarioAdapter {
        String id();
        String framework();
        boolean isAvailable(ClassLoader classLoader);
        List<ScenarioTask> discover(AdapterContext context) throws Exception;
        ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception;
    }

    public record AdapterContext(ClassLoader classLoader,
                                 List<Path> testRoots,
                                 Map<String, String> properties,
                                 DiscoverySelection discoverySelection) {
        public AdapterContext {
            testRoots = List.copyOf(testRoots);
            properties = Map.copyOf(properties);
            discoverySelection = discoverySelection == null ? DiscoverySelection.all() : discoverySelection;
        }
    }

    public record ExecutionContext(ClassLoader classLoader,
                                   WorkerId workerId,
                                   int attempt,
                                   Map<String, String> properties) {
        public ExecutionContext {
            properties = Map.copyOf(properties);
        }
    }

    public interface SchedulingStrategy {
        void load(Collection<ScenarioTask> tasks);
        ScenarioTask nextEligible(Predicate<ScenarioTask> eligible);
        int queued();
    }
}
