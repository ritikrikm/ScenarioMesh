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

    Analysis prepare(MavenProject project, String invocationId) {
        Plugin plugin = project.getPlugin(CLUECUMBER);
        if (plugin == null) {
            return Analysis.notPresent();
        }

        ConfigLocation location = configuredSourceDirectory(plugin);
        if (location == null || location.value() == null || location.value().isBlank()) {
            return Analysis.unsupported(
                    "Cluecumber reporting is configured but sourceJsonReportDirectory could not be resolved safely");
        }

        try {
            String resolved = resolveKnownExpressions(project, location.value().trim());
            Path basePath = Path.of(resolved);
            if (!basePath.isAbsolute()) {
                basePath = project.getBasedir().toPath().resolve(basePath);
            }
            Path runPath = basePath.toAbsolutePath().normalize()
                    .resolve("scenariomesh-" + safeInvocation(invocationId));

            // Cluecumber must read only artifacts from THIS ScenarioMesh invocation.
            // Pointing it at a run-scoped directory prevents stale/native JSON from a
            // previous or parallel execution being counted a second time.
            setChildValue(location.configuration(), SOURCE_DIRECTORY, runPath.toString());
            return Analysis.supported(runPath);
        } catch (RuntimeException exception) {
            return Analysis.unsupported(
                    "Cluecumber sourceJsonReportDirectory is not a usable path: " + location.value());
        }
    }

    private ConfigLocation configuredSourceDirectory(Plugin plugin) {
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().contains(REPORTING_GOAL)) {
                Xpp3Dom config = asDom(execution.getConfiguration());
                String value = childValue(config, SOURCE_DIRECTORY);
                if (value != null && !value.isBlank()) {
                    return new ConfigLocation(config, value);
                }
            }
        }
        Xpp3Dom config = asDom(plugin.getConfiguration());
        String value = childValue(config, SOURCE_DIRECTORY);
        return value == null ? null : new ConfigLocation(config, value);
    }

    private Xpp3Dom asDom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private String childValue(Xpp3Dom root, String name) {
        if (root == null) return null;
        Xpp3Dom child = root.getChild(name);
        return child == null ? null : child.getValue();
    }

    private void setChildValue(Xpp3Dom root, String name, String value) {
        if (root == null) {
            throw new IllegalArgumentException("missing Cluecumber configuration");
        }
        Xpp3Dom child = root.getChild(name);
        if (child == null) {
            child = new Xpp3Dom(name);
            root.addChild(child);
        }
        child.setValue(value);
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

    private String safeInvocation(String invocationId) {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("missing invocation id");
        }
        return invocationId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record ConfigLocation(Xpp3Dom configuration, String value) {}

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
