package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.SelectedTestClasses;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** JUnit Platform-specific backend probe loaded inside the target execution classloader. */
public final class JUnitPlatformBackendProbe {
    private static final Set<String> PROVEN_LEAF_OWNABLE_ENGINES = Set.of();
    private static final Set<String> PROVEN_SCOPED_OWNABLE_ENGINES = Set.of(
            "junit-jupiter", "junit-vintage", "cucumber", "junit-platform-suite");

    private JUnitPlatformBackendProbe() {}

    public static ProbeData inspect(ClassLoader targetClassLoader,
                                    List<Path> testRoots,
                                    List<String> includeClassNameRegexes,
                                    List<String> excludeClassNameRegexes,
                                    Set<String> adapterOwnedEngineIds) {
        if (testRoots == null || testRoots.isEmpty()) {
            return new ProbeData("NOT_DETECTED", List.of(), "no compiled test roots are available");
        }

        Set<String> additional = Set.copyOf(adapterOwnedEngineIds == null ? Set.of() : adapterOwnedEngineIds);
        List<TestEngine> engines = loadEngines(targetClassLoader);
        if (engines.isEmpty()) {
            return new ProbeData("NOT_DETECTED", List.of(), "no JUnit Platform TestEngine was loaded from the target classpath");
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(targetClassLoader);
            LauncherConfig config = LauncherConfig.builder()
                    .enableTestEngineAutoRegistration(false)
                    .addTestEngines(engines.toArray(TestEngine[]::new))
                    .build();
            Launcher launcher = LauncherFactory.create(config);

            DiscoverySelection selection = new DiscoverySelection(includeClassNameRegexes, excludeClassNameRegexes);
            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(SelectedTestClasses.scan(testRoots, selection).stream()
                            .map(className -> selectClass(className)).toList());
            if (!selection.includeClassNameRegexes().isEmpty() || !selection.excludeClassNameRegexes().isEmpty()) {
                request.filters(new MavenClassSelectionPostFilter(selection));
            }

            TestPlan plan = launcher.discover(request.build());
            List<BackendData> backends = new ArrayList<>();
            boolean hasOwnableExecutable = false;
            boolean hasUnownedExecutable = false;

            for (TestIdentifier root : plan.getRoots()) {
                String engineId = engineId(root);
                long executableLeaves = plan.getDescendants(root).stream()
                        .filter(TestIdentifier::isTest)
                        .filter(identifier -> plan.getChildren(identifier).isEmpty())
                        .count();

                boolean leafOwnable = PROVEN_LEAF_OWNABLE_ENGINES.contains(engineId);
                boolean scopedOwnable = PROVEN_SCOPED_OWNABLE_ENGINES.contains(engineId) || additional.contains(engineId);
                boolean ownable = leafOwnable || scopedOwnable;
                String granularity = leafOwnable ? "LEAF" : scopedOwnable ? "CONTAINER_OR_RUN" : "UNKNOWN";
                String ownership = ownable ? "OWNABLE" : "DETECTED_NOT_OWNABLE";
                Set<String> capabilities = leafOwnable
                        ? Set.of("DISCOVERY", "STABLE_LEAF_IDENTITY", "ISOLATED_LEAF_EXECUTION", "FILTER_EQUIVALENCE")
                        : scopedOwnable
                            ? Set.of("DISCOVERY", "STABLE_LEAF_IDENTITY", "LIFECYCLE_SCOPED_EXECUTION", "FILTER_EQUIVALENCE")
                            : Set.of("DISCOVERY", "STABLE_LEAF_IDENTITY");

                backends.add(new BackendData(engineId, "junit-platform", executableLeaves,
                        ownership, granularity, capabilities));
                if (executableLeaves > 0) {
                    if (ownable) hasOwnableExecutable = true;
                    else hasUnownedExecutable = true;
                }
            }

            if (hasUnownedExecutable) {
                return new ProbeData("DETECTED_NOT_OWNABLE", List.copyOf(backends),
                        "one or more engines expose executable leaves but ScenarioMesh has no proven execution contract for their lifecycle granularity");
            }
            if (hasOwnableExecutable) {
                return new ProbeData("OWNABLE", List.copyOf(backends),
                        "all executable JUnit Platform engines have a proven leaf or lifecycle-scoped ScenarioMesh execution contract");
            }
            return new ProbeData("NOT_DETECTED", List.copyOf(backends),
                    "JUnit Platform engines were loaded but none exposed executable leaves for this Maven selection");
        } catch (RuntimeException | LinkageError exception) {
            return new ProbeData("DETECTED_NOT_OWNABLE", List.of(),
                    "JUnit Platform backend probing failed: " + message(exception));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static List<TestEngine> loadEngines(ClassLoader classLoader) {
        List<TestEngine> engines = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (TestEngine engine : ServiceLoader.load(TestEngine.class, classLoader)) {
                if (!ids.add(engine.getId())) throw new IllegalStateException("duplicate JUnit Platform engine id '" + engine.getId() + "'");
                engines.add(engine);
            }
            return List.copyOf(engines);
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException("could not load JUnit Platform TestEngine services", exception);
        }
    }

    private static String engineId(TestIdentifier root) {
        try { return UniqueId.parse(root.getUniqueId()).getEngineId().orElse("unknown"); }
        catch (RuntimeException exception) { return "unknown"; }
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    public record BackendData(String id, String provider, long executableLeaves,
                              String ownership, String granularity, Set<String> capabilities) {
        public BackendData { capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities); }
    }

    public record ProbeData(String ownership, List<BackendData> backends, String reason) {
        public ProbeData { backends = List.copyOf(backends == null ? List.of() : backends); }
    }
}
