package io.scenariomesh.workerruntime;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.ScenarioIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryMainMavenSelectionTest {

    @Test
    void directJUnit4AppliesExactSurefireConfiguredIncludesAfterBroadDiscovery() {
        DiscoverySelection selection = new DiscoverySelection(
                List.of(".*"),
                List.of(),
                null,
                List.of("org/junit/tests/AllTests.java"),
                List.of("**/*$*"));

        List<ScenarioTask> filtered = DiscoveryMain.applyMavenSelection(
                "junit4-direct",
                List.of(task("org.junit.tests.AllTests"), task("org.junit.tests.SampleJUnit4Tests"),
                        task("org.junit.tests.SampleJUnit4Tests$NestedFixture")),
                selection);

        assertEquals(List.of("org.junit.tests.AllTests"),
                filtered.stream().map(task -> task.metadata().get("className")).toList());
    }

    @Test
    void directJUnit4FailsClosedForMethodLevelSurefireSelection() {
        DiscoverySelection selection = new DiscoverySelection(
                List.of(".*"),
                List.of(),
                null,
                List.of("**/CheckoutTest.java#happy*"),
                List.of());

        assertThrows(IllegalStateException.class, () -> DiscoveryMain.applyMavenSelection(
                "junit4-direct", List.of(task("example.CheckoutTest")), selection));
    }

    private ScenarioTask task(String className) {
        String selector = "class:" + className;
        return new ScenarioTask(
                ScenarioIds.from("junit4-direct", selector),
                className,
                "junit4-direct",
                "junit4-direct",
                null,
                null,
                selector,
                Set.of(),
                Map.of("className", className));
    }
}
