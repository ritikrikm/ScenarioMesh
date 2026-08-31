package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Classifies Maven executor plugin dependencies by semantic role.
 * Known JUnit Platform TestEngine roots can be moved onto ScenarioMesh's target classpath;
 * arbitrary/custom Surefire providers remain native Maven.
 */
final class MavenProviderDependencyCompatibility {
    private static final Set<String> SUPPORTED_ENGINE_ROOTS = Set.of(
            "org.junit.jupiter:junit-jupiter-engine",
            "io.cucumber:cucumber-junit-platform-engine",
            "org.junit.platform:junit-platform-suite-engine");

    Analysis analyze(Plugin plugin) {
        if (plugin == null || plugin.getDependencies() == null || plugin.getDependencies().isEmpty()) {
            return Analysis.supported(List.of());
        }
        List<Dependency> engines = new ArrayList<>();
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
            if ("org.junit.vintage:junit-vintage-engine".equals(coordinate)) {
                unsupported.add(coordinate + " requires the dedicated JUnit 4/Vintage equivalence gate");
            } else {
                unsupported.add(coordinate + " may alter Surefire/Failsafe provider semantics");
            }
        }
        return unsupported.isEmpty()
                ? Analysis.supported(engines)
                : Analysis.unsupported("executor plugin dependencies are not proven equivalent: " + String.join(", ", unsupported));
    }

    record Analysis(boolean supported, String reason, List<Dependency> engineDependencies) {
        Analysis { engineDependencies = List.copyOf(engineDependencies == null ? List.of() : engineDependencies); }
        static Analysis supported(List<Dependency> engines) { return new Analysis(true, null, engines); }
        static Analysis unsupported(String reason) { return new Analysis(false, reason, List.of()); }
    }
}
