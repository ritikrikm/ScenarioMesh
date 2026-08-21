package io.scenariomesh.adapter.testng;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class TestNgAdapterHardeningTest {
    private final TestNgAdapter adapter = new TestNgAdapter();

    @Test
    public void runtimeSkipIsExplicitlySkippedNotPassed() throws Exception {
        ScenarioTask task = taskFor(RuntimeSkipFixture.class, "skips", true);
        var result = adapter.execute(task, executionContext());
        Assert.assertEquals(result.status(), ResultStatus.SKIPPED);
        Assert.assertFalse(result.passed());
        Assert.assertTrue(result.buildSuccessful());
    }

    @Test
    public void disabledMethodIsExplicitlySkippedWithoutExecutingBody() throws Exception {
        DisabledFixture.executions.set(0);
        ScenarioTask task = taskFor(DisabledFixture.class, "disabled", false);
        var result = adapter.execute(task, executionContext());
        Assert.assertEquals(result.status(), ResultStatus.SKIPPED);
        Assert.assertEquals(DisabledFixture.executions.get(), 0);
    }

    @Test
    public void dataProviderMultiplicityFailsClosedDuringDiscovery() throws Exception {
        try {
            adapter.discover(discoveryContext(TestNgDataProviderFixture.class));
            Assert.fail("Expected data-provider discovery to fail closed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("data-provider multiplicity"), expected.getMessage());
        }
    }

    @Test
    public void invocationCountMultiplicityFailsClosedDuringDiscovery() throws Exception {
        try {
            adapter.discover(discoveryContext(TestNgInvocationCountFixture.class));
            Assert.fail("Expected invocationCount discovery to fail closed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("invocationCount=2"), expected.getMessage());
        }
    }

    @Test
    public void selectedCandidateClassLoadFailureIsNeverSilentlyDropped() throws Exception {
        Path root = Files.createTempDirectory("scenariomesh-testng-discovery");
        Path classFile = root.resolve("broken/BrokenTest.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{0});

        ClassLoader failingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("broken.BrokenTest".equals(name)) throw new NoClassDefFoundError("missing/Dependency");
                return super.loadClass(name, resolve);
            }
        };

        AdapterContext context = new AdapterContext(
                failingLoader, List.of(root), Map.of(),
                new DiscoverySelection(List.of(".*Test"), List.of()));

        try {
            adapter.discover(context);
            Assert.fail("Expected discovery to fail closed");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("broken.BrokenTest"), expected.getMessage());
            Assert.assertTrue(expected.getMessage().contains("missing/Dependency"), expected.getMessage());
        }
    }

    private AdapterContext discoveryContext(Class<?> fixture) throws Exception {
        Path root = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new AdapterContext(
                getClass().getClassLoader(), List.of(root), Map.of(),
                new DiscoverySelection(List.of("\\Q" + fixture.getName() + "\\E"), List.of()));
    }

    private ScenarioTask taskFor(Class<?> fixture, String methodName, boolean enabled) throws Exception {
        Method method = fixture.getDeclaredMethod(methodName);
        String selector = fixture.getName() + "#" + method.toGenericString();
        return new ScenarioTask(
                new ScenarioId(fixture.getName() + "." + methodName),
                fixture.getName() + "." + methodName,
                TestNgAdapter.ID, "testng", null, null, selector, Set.of(),
                Map.of("className", fixture.getName(), "methodName", methodName,
                        "enabled", Boolean.toString(enabled)));
    }

    private ExecutionContext executionContext() {
        return new ExecutionContext(getClass().getClassLoader(), new WorkerId("test-worker"), 1, Map.of());
    }

    public static final class RuntimeSkipFixture {
        @Test public void skips() { throw new SkipException("intentional skip"); }
    }

    public static final class DisabledFixture {
        private static final AtomicInteger executions = new AtomicInteger();
        @Test(enabled = false) public void disabled() { executions.incrementAndGet(); }
    }
}
