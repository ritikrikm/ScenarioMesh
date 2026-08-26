package io.scenariomesh.workerruntime;

import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequestBuilder;
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

/**
 * Runtime inventory of the test backends that are actually executable from the target test classpath.
 *
 * <p>Dependency names are intentionally not the source of truth here. For JUnit Platform repositories
 * we ask the Platform to load the target's {@link TestEngine}s and discover a real {@link TestPlan}.
 * Unknown engines are detected, but never assumed to be safe for ScenarioMesh leaf isolation.</p>
 */
public final class ExecutionBackendInventory {
    private static final Set<String> OWNABLE_JUNIT_PLATFORM_ENGINES = Set.of(
            "junit-jupiter",
            "cucumber",
            "junit-platform-suite");

    private ExecutionBackendInventory() {}

    public static Inventory inspect(
            ClassLoader targetClassLoader,
            List<Path> testRoots,
            List<String> includeClassNameRegexes,
            List<String> excludeClassNameRegexes) {
        if (testRoots == null || testRoots.isEmpty()) {
            return new Inventory(Ownership.NOT_DETECTED, List.of(), "no compiled test roots are available");
        }

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
            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectClasspathRoots(new HashSet<>(testRoots)));
            if (includeClassNameRegexes != null && !includeClassNameRegexes.isEmpty()) {
                request.filters(ClassNameFilter.includeClassNamePatterns(includeClassNameRegexes.toArray(String[]::new)));
            }
            if (excludeClassNameRegexes != null && !excludeClassNameRegexes.isEmpty()) {
                request.filters(ClassNameFilter.excludeClassNamePatterns(excludeClassNameRegexes.toArray(String[]::new)));
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
                boolean ownable = OWNABLE_JUNIT_PLATFORM_ENGINES.contains(engineId);
                BackendOwnership backendOwnership = ownable
                        ? BackendOwnership.OWNABLE
                        : BackendOwnership.DETECTED_NOT_OWNABLE;
                Set<Capability> capabilities = ownable
                        ? Set.of(Capability.DISCOVERY, Capability.STABLE_LEAF_IDENTITY,
                                Capability.ISOLATED_LEAF_EXECUTION, Capability.FILTER_EQUIVALENCE)
                        : Set.of(Capability.DISCOVERY, Capability.STABLE_LEAF_IDENTITY);
                backends.add(new Backend(engineId, "junit-platform", executableLeaves, backendOwnership, capabilities));
                if (executableLeaves > 0) {
                    if (ownable) hasOwnableExecutable = true;
                    else hasUnownedExecutable = true;
                }
            }

            if (hasUnownedExecutable) {
                return new Inventory(
                        Ownership.DETECTED_NOT_OWNABLE,
                        List.copyOf(backends),
                        "one or more JUnit Platform engines expose executable leaves but ScenarioMesh has no proven isolated-execution contract for them");
            }
            if (hasOwnableExecutable) {
                return new Inventory(Ownership.OWNABLE, List.copyOf(backends), "all executable JUnit Platform engines are owned by proven ScenarioMesh capabilities");
            }
            return new Inventory(Ownership.NOT_DETECTED, List.copyOf(backends), "JUnit Platform engines were loaded but none exposed executable leaves for this selection");
        } catch (RuntimeException | LinkageError exception) {
            return new Inventory(
                    Ownership.DETECTED_NOT_OWNABLE,
                    List.of(),
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
                if (!ids.add(engine.getId())) {
                    throw new IllegalStateException("duplicate JUnit Platform engine id '" + engine.getId() + "'");
                }
                engines.add(engine);
            }
            return List.copyOf(engines);
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException("could not load JUnit Platform TestEngine services", exception);
        }
    }

    private static String engineId(TestIdentifier root) {
        try {
            return UniqueId.parse(root.getUniqueId()).getEngineId().orElse("unknown");
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    public enum Ownership {
        OWNABLE,
        DETECTED_NOT_OWNABLE,
        NOT_DETECTED
    }

    public enum BackendOwnership {
        OWNABLE,
        DETECTED_NOT_OWNABLE
    }

    public enum Capability {
        DISCOVERY,
        STABLE_LEAF_IDENTITY,
        ISOLATED_LEAF_EXECUTION,
        FILTER_EQUIVALENCE,
        REPORT_EQUIVALENCE,
        RETRY_SAFE
    }

    public record Backend(
            String id,
            String provider,
            long executableLeaves,
            BackendOwnership ownership,
            Set<Capability> capabilities) {
        public Backend {
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }
    }

    public record Inventory(Ownership ownership, List<Backend> backends, String reason) {
        public Inventory {
            backends = List.copyOf(backends == null ? List.of() : backends);
        }

        public String summary() {
            if (backends.isEmpty()) return ownership + " (" + reason + ")";
            StringBuilder value = new StringBuilder(ownership.name()).append(" [");
            for (int i = 0; i < backends.size(); i++) {
                Backend backend = backends.get(i);
                if (i > 0) value.append(", ");
                value.append(backend.id()).append(":leaves=").append(backend.executableLeaves())
                        .append(":").append(backend.ownership());
            }
            return value.append("] (").append(reason).append(')').toString();
        }
    }
}
