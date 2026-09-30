package io.scenariomesh.adapter.junit4;

import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.core.ScenarioIds;
import io.scenariomesh.core.TaskMetadata;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnit4DirectAdapterTest {

    @Test
    void discoversOnlyWhenLegacyProviderIntentIsExplicit() throws Exception {
        JUnit4DirectAdapter adapter = new JUnit4DirectAdapter();
        Path root = Path.of(NativeSuite.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var selection = new io.scenariomesh.core.DiscoverySelection(
                List.of("^" + Pattern.quote(NativeSuite.class.getName()) + "$"), List.of());

        assertTrue(adapter.discover(new AdapterContext(getClass().getClassLoader(), List.of(root), Map.of(), selection)).isEmpty());

        Map<String, String> properties = Map.of(RuntimePropertyNames.MAVEN_PROVIDER_INTENT, JUnit4DirectAdapter.PROVIDER_INTENT);
        List<ScenarioTask> tasks = adapter.discover(
                new AdapterContext(getClass().getClassLoader(), List.of(root), properties, selection));
        assertEquals(1, tasks.size());
        assertEquals(NativeSuite.class.getName(), tasks.get(0).metadata().get("className"));
    }

    @Test
    void materializesNativeSuitePassSkipAndIgnoreOutcomes() throws Exception {
        JUnit4DirectAdapter adapter = new JUnit4DirectAdapter();
        String selector = "class:" + NativeSuite.class.getName();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("className", NativeSuite.class.getName());
        metadata.put(TaskMetadata.RUNTIME_MATERIALIZER, "true");
        metadata.put(TaskMetadata.EXECUTION_SCOPE_ID, "junit4-class:" + NativeSuite.class.getName());
        metadata.put(TaskMetadata.EXECUTION_SCOPE_KIND, "junit4-class-or-suite");
        ScenarioTask parent = new ScenarioTask(
                ScenarioIds.from(JUnit4DirectAdapter.ID, selector), NativeSuite.class.getName(),
                JUnit4DirectAdapter.ID, "junit4-direct", null, null, selector, Set.of(), Map.copyOf(metadata));

        var execution = adapter.executeWorkUnit(List.of(parent),
                new ExecutionContext(getClass().getClassLoader(), new WorkerId("worker-1"), 1, Map.of()));

        assertEquals(4, execution.results().size());
        assertEquals(2, execution.results().stream().filter(result -> result.status() == ResultStatus.PASSED).count());
        assertEquals(2, execution.results().stream().filter(result -> result.status() == ResultStatus.SKIPPED).count());
        assertTrue(execution.tasks().stream().allMatch(task ->
                parent.id().value().equals(task.metadata().get(TaskMetadata.PARENT_MATERIALIZER_ID))));
    }

    @RunWith(Suite.class)
    @Suite.SuiteClasses({Passing.class, AssumptionSkipped.class, IgnoredCase.class, LegacyTestCase.class})
    public static class NativeSuite {}

    public static class Passing {
        @org.junit.Test public void works() {}
    }

    public static class AssumptionSkipped {
        @org.junit.Test public void skips() { Assume.assumeTrue(false); }
    }

    public static class IgnoredCase {
        @Ignore @org.junit.Test public void ignored() {}
    }

    public static class LegacyTestCase extends junit.framework.TestCase {
        public void testLegacy() {}
    }
}
