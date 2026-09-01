package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capability-based classification of Surefire/Failsafe plugin dependencies.
 *
 * <p>A plugin dependency is never treated as an ordinary project dependency. Known TestEngine
 * roots can be resolved onto the ScenarioMesh target realm. Known Surefire provider artifacts are
 * provider selectors: their code is not copied into the target realm, but their framework intent
 * is recorded so compatibility analysis can ensure ScenarioMesh owns the same framework. Unknown
 * provider/plugin extensions remain fail-closed because arbitrary provider code can change
 * discovery, class loading, reporting and fork semantics.</p>
 */
final class MavenProviderDependencyCompatibility {
    private static final Set<String> SUPPORTED_ENGINE_ROOTS = Set.of(
            "org.junit.jupiter:junit-jupiter-engine",
            "io.cucumber:cucumber-junit-platform-engine",
            "org.junit.platform:junit-platform-suite-engine");

    private static final Map<String, String> SUPPORTED_PROVIDER_SELECTORS = Map.of(
            "org.apache.maven.surefire:surefire-junit-platform", "junit-platform",
            "org.apache.maven.surefire:surefire-testng", "testng");

    Analysis analyze(Plugin plugin) {
        if (plugin == null || plugin.getDependencies() == null || plugin.getDependencies().isEmpty()) {
            return Analysis.supported(List.of(), Set.of());
        }
        List<Dependency> engines = new ArrayList<>();
        java.util.LinkedHashSet<String> providerIntents = new java.util.LinkedHashSet<>();
        List<String> unsupported = new ArrayList<>();
        for (Dependency dependency : plugin.getDependencies()) {
            String coordinate = dependency.getGroupId() + ":" + dependency.getArtifactId();
            if (SUPPORTED_ENGINE_ROOTS.contains(coordinate)) {
                if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
                    unsupported.add(coordinate + " has no explicit version");
                } else {
                    engines.add(dependency);
                }
                continue;
            }
            String providerIntent = SUPPORTED_PROVIDER_SELECTORS.get(coordinate);
            if (providerIntent != null) {
                if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
                    unsupported.add(coordinate + " has no explicit version");
                } else {
                    providerIntents.add(providerIntent);
                }
                continue;
            }
            if ("org.apache.maven.surefire:surefire-junit47".equals(coordinate)
                    || "org.apache.maven.surefire:surefire-junit4".equals(coordinate)
                    || "org.junit.vintage:junit-vintage-engine".equals(coordinate)) {
                unsupported.add(coordinate + " requires the dedicated JUnit 4/Vintage equivalence gate");
            } else {
                unsupported.add(coordinate + " is an unregistered provider/plugin extension and may alter executor semantics");
            }
        }
        return unsupported.isEmpty()
                ? Analysis.supported(engines, providerIntents)
                : Analysis.unsupported("executor plugin dependencies are not proven equivalent: "
                        + String.join(", ", unsupported));
    }

    record Analysis(boolean supported, String reason, List<Dependency> engineDependencies,
                    Set<String> providerIntents) {
        Analysis {
            engineDependencies = List.copyOf(engineDependencies == null ? List.of() : engineDependencies);
            providerIntents = Set.copyOf(providerIntents == null ? Set.of() : providerIntents);
        }
        static Analysis supported(List<Dependency> engines, Set<String> providerIntents) {
            return new Analysis(true, null, engines, providerIntents);
        }
        static Analysis unsupported(String reason) {
            return new Analysis(false, reason, List.of(), Set.of());
        }
    }
}
