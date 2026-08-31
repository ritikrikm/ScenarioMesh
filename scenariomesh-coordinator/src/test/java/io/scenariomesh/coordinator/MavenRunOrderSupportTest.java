package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.RuntimePropertyNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenRunOrderSupportTest {
    @TempDir Path temporaryDirectory;

    @Test
    void alphabeticalOrdersClassBucketsWithoutSplittingClassTasks() {
        List<ScenarioTask> tasks = List.of(
                task("z-1", "example.ZTest"),
                task("a-1", "example.ATest"),
                task("z-2", "example.ZTest"));

        List<ScenarioTask> ordered = MavenRunOrderSupport.order(
                request(Map.of(RuntimePropertyNames.MAVEN_RUN_ORDER, "alphabetical")), tasks);

        assertEquals(List.of("a-1", "z-1", "z-2"), ids(ordered));
    }

    @Test
    void reverseAlphabeticalMatchesReverseClassOrdering() {
        List<ScenarioTask> tasks = List.of(task("a", "A"), task("c", "C"), task("b", "B"));
        List<ScenarioTask> ordered = MavenRunOrderSupport.order(
                request(Map.of(RuntimePropertyNames.MAVEN_RUN_ORDER, "reversealphabetical")), tasks);
        assertEquals(List.of("c", "b", "a"), ids(ordered));
    }

    @Test
    void seededRandomMatchesJavaCollectionsShuffleAndIsReproducible() {
        List<ScenarioTask> tasks = List.of(
                task("a", "A"), task("b", "B"), task("c", "C"), task("d", "D"));
        Map<String, String> properties = Map.of(
                RuntimePropertyNames.MAVEN_RUN_ORDER, "random",
                RuntimePropertyNames.MAVEN_RUN_ORDER_RANDOM_SEED, "8675309");

        List<String> first = ids(MavenRunOrderSupport.order(request(properties), tasks));
        List<String> second = ids(MavenRunOrderSupport.order(request(properties), tasks));

        assertEquals(first, second);
        assertEquals(List.of("c", "b", "a", "d"), first);
    }

    @Test
    void filesystemRetainsDiscoveryAdmissionOrder() {
        List<ScenarioTask> tasks = List.of(task("c", "C"), task("a", "A"), task("b", "B"));
        List<ScenarioTask> ordered = MavenRunOrderSupport.order(
                request(Map.of(RuntimePropertyNames.MAVEN_RUN_ORDER, "filesystem")), tasks);
        assertEquals(List.of("c", "a", "b"), ids(ordered));
    }

    private RunRequest request(Map<String, String> properties) {
        return new RunRequest(
                temporaryDirectory,
                List.of(),
                List.of(),
                Map.of(),
                ScenarioMeshConfig.defaults(temporaryDirectory.resolve("target")),
                DiscoverySelection.all(),
                List.of(),
                properties);
    }

    private ScenarioTask task(String id, String className) {
        return new ScenarioTask(
                new ScenarioId(id), id, "junit-platform", "junit-platform",
                null, null, id, Set.of(), Map.of("className", className));
    }

    private List<String> ids(List<ScenarioTask> tasks) {
        return tasks.stream().map(task -> task.id().value()).toList();
    }
}
