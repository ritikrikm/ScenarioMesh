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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

/** JUnit Platform-specific backend probe loaded inside the target execution classloader. */
public final class JUnitPlatformBackendProbe {
    private JUnitPlatformBackendProbe() {}

    public static ProbeData inspect(ClassLoader targetClassLoader,
                                    List<Path> testRoots,
                                    List<String> includeClassNameRegexes,
                                    List<String> excludeClassNameRegexes,
                                    Set<String> adapterOwnedEngineIds) {
        if (testRoots == null || testRoots.isEmpty()) {
            return new ProbeData("NOT_DETECTED", List.of(), "no compiled test roots are available");
        }

        Set<String> adapterDeclared = Set.copyOf(adapterOwnedEngineIds == null ? Set.of() : adapterOwnedEngineIds);
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
            List<String> selectedClasses = SelectedTestClasses.scan(testRoots, selection);
            List<org.junit.platform.engine.DiscoverySelector> selectors = new ArrayList<>(selectedClasses.stream()
                    .map(className -> (org.junit.platform.engine.DiscoverySelector) selectClass(className)).toList());

            // A conventional classpath:/features selector is evidence only when that resource exists.
            // Presence of the Cucumber engine alone is never enough to fabricate a selector.
            boolean selectedFeaturesResource = engines.stream().anyMatch(engine -> "cucumber".equals(engine.getId()))
                    && hasClasspathResource(testRoots, "features");
            if (selectedFeaturesResource) selectors.add(selectClasspathResource("features"));

            JUnitPlatformExecutionContracts.DiscoveryShape discoveryShape =
                    JUnitPlatformExecutionContracts.DiscoveryShape.of(!selectedClasses.isEmpty(), selectedFeaturesResource);

            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request().selectors(selectors);
            if (!selection.includeClassNameRegexes().isEmpty() || !selection.excludeClassNameRegexes().isEmpty()) {
                request.filters(new MavenClassSelectionPostFilter(selection));
            }

            TestPlan plan = launcher.discover(request.build());
            Map<String, TestEngine> enginesById = enginesById(engines);
            Set<String> provenEngineIdentities = provenEngineIdentities(engines, adapterDeclared);
            List<BackendData> backends = new ArrayList<>();
            List<String> proofProfiles = new ArrayList<>();
            List<String> rejectionReasons = new ArrayList<>();
            boolean hasOwnableExecutable = false;
            boolean hasUnownedExecutable = false;

            for (TestIdentifier root : plan.getRoots()) {
                String engineId = engineId(root);
                long executableLeaves = plan.getDescendants(root).stream()
                        .filter(TestIdentifier::isTest)
                        .filter(identifier -> plan.getChildren(identifier).isEmpty())
                        .count();

                TestEngine engine = enginesById.get(engineId);
                String implementationClass = engine == null ? "unknown" : engine.getClass().getName();
                JUnitPlatformEngineVersion.VersionEvidence versionEvidence = engine == null
                        ? new JUnitPlatformEngineVersion.VersionEvidence("unknown", "unresolved", "unknown")
                        : JUnitPlatformEngineVersion.resolve(engine);
                String version = versionEvidence.version();
                Set<String> nestedEngineIds = nestedEngineIds(plan, root);
                JUnitPlatformExecutionContracts.Decision decision = JUnitPlatformExecutionContracts.prove(
                        new JUnitPlatformExecutionContracts.Evidence(
                                engineId,
                                implementationClass,
                                version,
                                discoveryShape,
                                nestedEngineIds,
                                provenEngineIdentities,
                                adapterDeclared.contains(engineId),
                                executableLeaves));

                String ownership = decision.ownable() ? "OWNABLE" : "DETECTED_NOT_OWNABLE";
                backends.add(new BackendData(engineId, "junit-platform", executableLeaves,
                        ownership, decision.granularity(), decision.capabilities()));

                if (executableLeaves > 0) {
                    if (decision.ownable()) {
                        hasOwnableExecutable = true;
                        proofProfiles.add(engineId + "@" + versionEvidence.diagnostic() + "=" + decision.profile());
                    } else {
                        hasUnownedExecutable = true;
                        rejectionReasons.add(engineId + "@" + versionEvidence.diagnostic() + ": " + decision.reason());
                    }
                }
            }

            if (hasUnownedExecutable) {
                return new ProbeData("DETECTED_NOT_OWNABLE", List.copyOf(backends),
                        "one or more executable JUnit Platform backends are outside the proven execution-capability envelope: "
                                + String.join("; ", rejectionReasons));
            }
            if (hasOwnableExecutable) {
                return new ProbeData("OWNABLE", List.copyOf(backends),
                        "all executable JUnit Platform backends matched proven execution profiles: "
                                + String.join(", ", proofProfiles));
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

    private static Set<String> provenEngineIdentities(List<TestEngine> engines, Set<String> adapterDeclared) {
        Set<String> proven = new LinkedHashSet<>();
        for (TestEngine engine : engines) {
            JUnitPlatformEngineVersion.VersionEvidence versionEvidence = JUnitPlatformEngineVersion.resolve(engine);
            if (adapterDeclared.contains(engine.getId())
                    && JUnitPlatformExecutionContracts.identitySupported(
                            engine.getId(), engine.getClass().getName(), versionEvidence.version())) {
                proven.add(engine.getId());
            }
        }
        return Set.copyOf(proven);
    }

    private static Set<String> nestedEngineIds(TestPlan plan, TestIdentifier root) {
        Set<String> nested = new LinkedHashSet<>();
        for (TestIdentifier identifier : plan.getDescendants(root)) {
            try {
                List<UniqueId.Segment> segments = UniqueId.parse(identifier.getUniqueId()).getSegments();
                for (int i = 1; i < segments.size(); i++) {
                    UniqueId.Segment segment = segments.get(i);
                    if ("engine".equals(segment.getType())) nested.add(segment.getValue());
                }
            } catch (RuntimeException ignored) {
                // Invalid unique ids are handled by the engine contract itself through failed proof.
            }
        }
        return Set.copyOf(nested);
    }

    private static Map<String, TestEngine> enginesById(List<TestEngine> engines) {
        Map<String, TestEngine> result = new LinkedHashMap<>();
        for (TestEngine engine : engines) result.put(engine.getId(), engine);
        return Map.copyOf(result);
    }

    private static boolean hasClasspathResource(List<Path> testRoots, String resource) {
        return testRoots.stream().anyMatch(root -> java.nio.file.Files.exists(root.resolve(resource)));
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

    public record BackendData(String id, String provider, long executableLeaves,
                              String ownership, String granularity, Set<String> capabilities) {
        public BackendData {
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }
    }

    public record ProbeData(String ownership, List<BackendData> backends, String reason) {
        public ProbeData {
            backends = List.copyOf(backends == null ? List.of() : backends);
        }
    }
}
