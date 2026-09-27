package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JUnitPlatformInheritedSkipTest {

    @Test
    void classLevelSkipIsInheritedBySelectedLeaf() {
        String scope = "[engine:junit-jupiter]/[class:io.scenariomesh.adapter.junitplatform.DisabledFixture]";
        String selector = scope + "/[method:skippedLeaf()]";
        ScenarioTask task = new ScenarioTask(
                new ScenarioId("disabled-leaf"),
                "skippedLeaf()",
                JUnitPlatformAdapter.ID,
                "junit5",
                null,
                null,
                selector,
                Set.of(),
                Map.of(
                        JUnitPlatformAdapter.META_SCOPE_ID, scope,
                        JUnitPlatformAdapter.META_SCOPE_SELECTOR, scope,
                        JUnitPlatformAdapter.META_SCOPE_KIND, "class-or-suite",
                        JUnitPlatformAdapter.META_REQUIRED_ENGINE_ID, "junit-jupiter"));

        var execution = new JUnitPlatformAdapter().executeWorkUnit(
                List.of(task),
                new ExecutionContext(
                        Thread.currentThread().getContextClassLoader(),
                        new WorkerId("worker-test"),
                        1,
                        Map.of()));

        assertEquals(1, execution.results().size());
        assertEquals(ResultStatus.SKIPPED, execution.results().get(0).status());
        assertEquals("fixture disabled", execution.results().get(0).failureMessage());
    }
}

@Disabled("fixture disabled")
class DisabledFixture {
    @Test
    void skippedLeaf() {
    }
}
