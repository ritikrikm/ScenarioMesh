package io.scenariomesh.workerruntime;

import io.scenariomesh.adapter.junitplatform.MavenClassSelectionPostFilter;
import io.scenariomesh.core.DiscoverySelection;
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

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/** Runtime inventory of executable test backends and their proven ScenarioMesh ownership granularity. */
public final class ExecutionBackendInventory {
    private static final Set<String> PROVEN_LEAF_OWNABLE_ENGINES = Set.of();
    private static final Set<String> PROVEN_SCOPED_OWNABLE_ENGINES = Set.of(
            "junit-jupiter", "cucumber", "junit-platform-suite");

    private ExecutionBackendInventory() {}

    public static Inventory inspect(ClassLoader targetClassLoader, List<Path> testRoots,
                                    List<String> includeClassNameRegexes, List<String> excludeClassNameRegexes) {
        return inspect(targetClassLoader, testRoots, includeClassNameRegexes, excludeClassNameRegexes, Set.of());
    }

    /**
     * Additional engine ids may only come from loaded ScenarioAdapter capability declarations.
     * Declaring an engine makes it lifecycle-scoped ownable; it never grants leaf-isolation implicitly.
     */
    public static Inventory inspect(ClassLoader targetClassLoader, List<Path> testRoots,
                                    List<String> includeClassNameRegexes, List<String> excludeClassNameRegexes,
                                    Set<String> adapterOwnedEngineIds) {
        if (testRoots == null || testRoots.isEmpty()) {
            return new Inventory(Ownership.NOT_DETECTED, List.of(), "no compiled test roots are available");
        }

        Set<String> additional = Set.copyOf(adapterOwnedEngineIds == null ? Set.of() : adapterOwnedEngineIds);
        List<TestEngine> engines = loadEngines(targetClassLoader);
        if (engines.isEmpty()) {
            return new Inventory(Ownership.NOT_DETECTED, List.of(), "no JUnit Platform TestEngine was loaded from the target classpath");
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
                    .selectors(selectClasspathRoots(new HashSet<>(testRoots)));
            if (!selection.includeClassNameRegexes().isEmpty() || !selection.excludeClassNameRegexes().isEmpty()) {
                request.filters(new MavenClassSelectionPostFilter(selection));
            }

            TestPlan plan = launcher.discover(request.build());
            List<Backend> backends = new ArrayList<>();
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
                ExecutionGranularity granularity = leafOwnable
                        ? ExecutionGranularity.LEAF
                        : scopedOwnable ? ExecutionGranularity.CONTAINER_OR_RUN : ExecutionGranularity.UNKNOWN;
                BackendOwnership backendOwnership = ownable ? BackendOwnership.OWNABLE : BackendOwnership.DETECTED_NOT_OWNABLE;
                Set<Capability> capabilities = leafOwnable
                        ? Set.of(Capability.DISCOVERY, Capability.STABLE_LEAF_IDENTITY,
                                Capability.ISOLATED_LEAF_EXECUTION, Capability.FILTER_EQUIVALENCE)
                        : scopedOwnable
                            ? Set.of(Capability.DISCOVERY, Capability.STABLE_LEAF_IDENTITY,
                                    Capability.LIFECYCLE_SCOPED_EXECUTION, Capability.FILTER_EQUIVALENCE)
                            : Set.of(Capability.DISCOVERY, Capability.STABLE_LEAF_IDENTITY);

                backends.add(new Backend(engineId, "junit-platform", executableLeaves,
                        backendOwnership, granularity, capabilities));
                if (executableLeaves > 0) {
                    if (ownable) hasOwnableExecutable = true;
                    else hasUnownedExecutable = true;
                }
            }

            if (hasUnownedExecutable) {
                return new Inventory(Ownership.DETECTED_NOT_OWNABLE, List.copyOf(backends),
                        "one or more engines expose executable leaves but ScenarioMesh has no proven execution contract for their lifecycle granularity");
            }
            if (hasOwnableExecutable) {
                return new Inventory(Ownership.OWNABLE, List.copyOf(backends),
                        "all executable JUnit Platform engines have a proven leaf or lifecycle-scoped ScenarioMesh execution contract");
            }
            return new Inventory(Ownership.NOT_DETECTED, List.copyOf(backends),
                    "JUnit Platform engines were loaded but none exposed executable leaves for this Maven selection");
        } catch (RuntimeException | LinkageError exception) {
            return new Inventory(Ownership.DETECTED_NOT_OWNABLE, List.of(),
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

    public enum Ownership { OWNABLE, DETECTED_NOT_OWNABLE, NOT_DETECTED }
    public enum BackendOwnership { OWNABLE, DETECTED_NOT_OWNABLE }
    public enum ExecutionGranularity { LEAF, CLASS, CONTAINER_OR_RUN, UNKNOWN }

    public enum Capability {
        DISCOVERY, STABLE_LEAF_IDENTITY, ISOLATED_LEAF_EXECUTION,
        LIFECYCLE_SCOPED_EXECUTION, FILTER_EQUIVALENCE, REPORT_EQUIVALENCE, RETRY_SAFE
    }

    public record Backend(String id, String provider, long executableLeaves,
                          BackendOwnership ownership, ExecutionGranularity granularity,
                          Set<Capability> capabilities) {
        public Backend { capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities); }
    }

    public record Inventory(Ownership ownership, List<Backend> backends, String reason) {
        public Inventory { backends = List.copyOf(backends == null ? List.of() : backends); }
        public String summary() {
            if (backends.isEmpty()) return ownership + " (" + reason + ")";
            StringBuilder value = new StringBuilder(ownership.name()).append(" [");
            for (int i = 0; i < backends.size(); i++) {
                Backend backend = backends.get(i);
                if (i > 0) value.append(", ");
                value.append(backend.id()).append(":leaves=").append(backend.executableLeaves())
                        .append(":").append(backend.ownership())
                        .append(":granularity=").append(backend.granularity());
            }
            return value.append("] (").append(reason).append(')').toString();
        }
    }
}
