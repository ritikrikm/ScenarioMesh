package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Path;
import java.util.Map;

/** Detects downstream Maven report consumers whose input artifacts ScenarioMesh must preserve. */
final class DownstreamReportCompatibility {
    private static final String CLUECUMBER = "com.trivago.rta:cluecumber-report-plugin";
    private static final String REPORTING_GOAL = "reporting";
    private static final String SOURCE_DIRECTORY = "sourceJsonReportDirectory";

    Analysis analyze(MavenProject project) {
        Plugin plugin = project.getPlugin(CLUECUMBER);
        if (plugin == null) {
            return Analysis.notPresent();
        }

        String configured = configuredSourceDirectory(plugin);
        if (configured == null || configured.isBlank()) {
            return Analysis.unsupported(
                    "Cluecumber reporting is configured but sourceJsonReportDirectory could not be resolved safely");
        }

        try {
            String resolved = resolveKnownExpressions(project, configured.trim());
            Path path = Path.of(resolved);
            if (!path.isAbsolute()) {
                path = project.getBasedir().toPath().resolve(path);
            }
            return Analysis.supported(path.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return Analysis.unsupported(
                    "Cluecumber sourceJsonReportDirectory is not a usable path: " + configured);
        }
    }

    private String configuredSourceDirectory(Plugin plugin) {
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().contains(REPORTING_GOAL)) {
                String value = childValue(execution.getConfiguration(), SOURCE_DIRECTORY);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return childValue(plugin.getConfiguration(), SOURCE_DIRECTORY);
    }

    private String childValue(Object configuration, String name) {
        if (!(configuration instanceof Xpp3Dom root)) {
            return null;
        }
        Xpp3Dom child = root.getChild(name);
        return child == null ? null : child.getValue();
    }

    private String resolveKnownExpressions(MavenProject project, String value) {
        String buildDirectory = project.getBuild() == null ? null : project.getBuild().getDirectory();
        String basedir = project.getBasedir() == null ? null : project.getBasedir().getAbsolutePath();
        String resolved = value;
        if (buildDirectory != null) {
            resolved = resolved.replace("${project.build.directory}", buildDirectory);
        }
        if (basedir != null) {
            resolved = resolved
                    .replace("${project.basedir}", basedir)
                    .replace("${basedir}", basedir);
        }
        if (resolved.contains("${")) {
            throw new IllegalArgumentException("unresolved Maven expression");
        }
        return resolved;
    }

    record Analysis(boolean present, boolean supported, Path sourceJsonDirectory, String reason) {
        static Analysis notPresent() {
            return new Analysis(false, true, null, null);
        }

        static Analysis supported(Path directory) {
            return new Analysis(true, true, directory, null);
        }

        static Analysis unsupported(String reason) {
            return new Analysis(true, false, null, reason);
        }

        Map<String, String> runtimeProperties() {
            if (!present || !supported || sourceJsonDirectory == null) {
                return Map.of();
            }
            return Map.of(RuntimePropertyNames.CLUECUMBER_JSON_DIRECTORY, sourceJsonDirectory.toString());
        }
    }
}
