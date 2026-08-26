package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Protects transparent Failsafe takeover from changing the contract seen by
 * arbitrary post-integration-test/verify consumers.
 *
 * <p>ScenarioMesh only allows downstream executions whose contract it already
 * owns or explicitly adapts. Unknown consumers remain native Maven pass-through
 * material; guessing what files/properties they consume would create silent
 * compatibility failures.</p>
 */
final class DownstreamLifecycleCompatibility {
    private static final Set<String> DOWNSTREAM_PHASES = Set.of("post-integration-test", "verify");
    private static final Set<String> KNOWN_COORDINATES = Set.of(
            "org.apache.maven.plugins:maven-failsafe-plugin",
            "io.scenariomesh:scenariomesh-maven-plugin",
            "com.trivago.rta:cluecumber-report-plugin");

    Analysis analyze(MavenProject project, ProjectCompatibilityDetector.ExecutorKind executorKind) {
        if (executorKind != ProjectCompatibilityDetector.ExecutorKind.FAILSAFE) {
            return Analysis.supported("Surefire takeover does not replace the integration-test lifecycle");
        }
        if (project.getBuildPlugins() == null) {
            return Analysis.supported("no downstream build plugins are configured");
        }

        List<String> unknown = new ArrayList<>();
        for (Plugin plugin : project.getBuildPlugins()) {
            String coordinate = coordinate(plugin);
            if (KNOWN_COORDINATES.contains(coordinate) || plugin.getExecutions() == null) continue;
            for (PluginExecution execution : plugin.getExecutions()) {
                String phase = trim(execution.getPhase());
                if (!DOWNSTREAM_PHASES.contains(phase)) continue;
                unknown.add(coordinate + " execution '" + executionId(execution) + "' bound to " + phase);
            }
        }

        if (!unknown.isEmpty()) {
            return Analysis.unsupported(
                    "unknown downstream Maven lifecycle consumer(s) are active after integration-test: "
                            + String.join(", ", unknown)
                            + ". ScenarioMesh cannot prove their artifact/property contract, so native Maven execution is preserved.");
        }
        return Analysis.supported("all downstream integration lifecycle consumers have known contracts");
    }

    private String coordinate(Plugin plugin) {
        String groupId = trim(plugin.getGroupId());
        if (groupId == null) groupId = "org.apache.maven.plugins";
        return groupId + ":" + String.valueOf(plugin.getArtifactId());
    }

    private String executionId(PluginExecution execution) {
        String id = trim(execution.getId());
        return id == null ? "<unnamed>" : id;
    }

    private String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Analysis(boolean supported, String reason) {
        static Analysis supported(String reason) { return new Analysis(true, reason); }
        static Analysis unsupported(String reason) { return new Analysis(false, reason); }
    }
}
