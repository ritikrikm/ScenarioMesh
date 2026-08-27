package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the Java executable Maven Surefire/Failsafe would use for tests. */
final class TestJvmResolver {
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    Path resolve(MavenProject project,
                 MavenSession session,
                 ToolchainManager toolchains,
                 String executor,
                 Integer executionOrdinal) {
        Plugin plugin = project.getPlugin("failsafe".equals(normalize(executor)) ? FAILSAFE : SUREFIRE);
        PluginExecution execution = selectedExecution(plugin, executor, executionOrdinal);

        String explicitJvm = firstNonBlank(
                scalar(execution == null ? null : execution.getConfiguration(), "jvm", project, session),
                scalar(plugin == null ? null : plugin.getConfiguration(), "jvm", project, session));
        if (explicitJvm != null) return validateJavaExecutable(Path.of(explicitJvm), "executor <jvm>");

        Map<String, String> requirements = mergedToolchainRequirements(plugin, execution, project, session);
        if (!requirements.isEmpty()) {
            List<Toolchain> matches = toolchains == null ? List.of() : toolchains.getToolchains(session, "jdk", requirements);
            if (matches == null || matches.isEmpty()) {
                throw new IllegalStateException("No Maven JDK toolchain matches Surefire/Failsafe jdkToolchain requirements " + requirements);
            }
            return javaFromToolchain(matches.get(0), "jdkToolchain " + requirements);
        }

        if (toolchains != null) {
            Toolchain buildToolchain = toolchains.getToolchainFromBuildContext("jdk", session);
            if (buildToolchain != null) return javaFromToolchain(buildToolchain, "Maven build-context JDK toolchain");
        }
        return currentJavaExecutable();
    }

    private PluginExecution selectedExecution(Plugin plugin, String executor, Integer ordinal) {
        if (plugin == null || plugin.getExecutions() == null) return null;
        List<PluginExecution> candidates = new ArrayList<>();
        boolean failsafe = "failsafe".equals(normalize(executor));
        for (PluginExecution execution : plugin.getExecutions()) {
            if (failsafe) {
                if (execution.getGoals() != null && execution.getGoals().stream().anyMatch("integration-test"::equals)) candidates.add(execution);
            } else if ("default-test".equals(execution.getId())
                    || (execution.getGoals() != null && execution.getGoals().stream().anyMatch("test"::equals))) {
                candidates.add(execution);
            }
        }
        if (candidates.isEmpty()) return null;
        if (ordinal == null) {
            if (candidates.size() == 1) return candidates.get(0);
            // Multiple executions are safe to resolve without an ordinal only when none
            // overrides JVM selection; the plugin-wide/main toolchain is then common to all.
            if (candidates.stream().noneMatch(this::hasJvmSelection)) return null;
            throw new IllegalStateException("Multiple Maven test executions use execution-specific JVM/toolchain selection; "
                    + "ScenarioMesh requires an execution-specific JVM mapping before takeover");
        }
        if (ordinal < 0 || ordinal >= candidates.size()) {
            throw new IllegalStateException("ScenarioMesh test-JVM execution ordinal " + ordinal
                    + " is outside the Maven execution set of size " + candidates.size());
        }
        return candidates.get(ordinal);
    }

    private boolean hasJvmSelection(PluginExecution execution) {
        Xpp3Dom root = execution.getConfiguration() instanceof Xpp3Dom dom ? dom : null;
        return root != null && (root.getChild("jvm") != null || root.getChild("jdkToolchain") != null);
    }

    private Map<String, String> mergedToolchainRequirements(Plugin plugin,
                                                             PluginExecution execution,
                                                             MavenProject project,
                                                             MavenSession session) {
        Map<String, String> result = new LinkedHashMap<>();
        readToolchain(plugin == null ? null : plugin.getConfiguration(), result, project, session);
        readToolchain(execution == null ? null : execution.getConfiguration(), result, project, session);
        return Map.copyOf(result);
    }

    private void readToolchain(Object configuration, Map<String, String> destination,
                               MavenProject project, MavenSession session) {
        Xpp3Dom root = configuration instanceof Xpp3Dom dom ? dom : null;
        if (root == null) return;
        Xpp3Dom toolchain = root.getChild("jdkToolchain");
        if (toolchain == null) return;
        for (Xpp3Dom child : toolchain.getChildren()) {
            if (child.getChildCount() > 0) throw new IllegalStateException("Nested <jdkToolchain> requirement '" + child.getName() + "' is not reproducible");
            String value = resolve(child.getValue(), project, session);
            if (value != null && !value.isBlank()) destination.put(child.getName(), value);
        }
    }

    private String scalar(Object configuration, String name, MavenProject project, MavenSession session) {
        Xpp3Dom root = configuration instanceof Xpp3Dom dom ? dom : null;
        if (root == null) return null;
        Xpp3Dom node = root.getChild(name);
        if (node == null) return null;
        if (node.getChildCount() > 0) throw new IllegalStateException("Structured <" + name + "> is not reproducible");
        return resolve(node.getValue(), project, session);
    }

    private String resolve(String raw, MavenProject project, MavenSession session) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("${") && value.endsWith("}") && value.indexOf("${", 2) < 0) {
            String key = value.substring(2, value.length() - 1);
            String resolved = session.getUserProperties().getProperty(key);
            if (resolved == null) resolved = session.getSystemProperties().getProperty(key);
            if (resolved == null && project.getProperties() != null) resolved = project.getProperties().getProperty(key);
            if (resolved == null) throw new IllegalStateException("Unresolved Maven property " + value + " in test-JVM configuration");
            return resolved.trim();
        }
        if (value.contains("${")) throw new IllegalStateException("Composite Maven expression in test-JVM configuration is not yet reproducible: " + value);
        return value;
    }

    private Path javaFromToolchain(Toolchain toolchain, String source) {
        String executable = toolchain.findTool("java");
        if (executable == null || executable.isBlank()) throw new IllegalStateException(source + " does not expose a java executable");
        return validateJavaExecutable(Path.of(executable), source);
    }

    private Path validateJavaExecutable(Path path, String source) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) throw new IllegalStateException(source + " points to a missing Java executable: " + absolute);
        if (!Files.isExecutable(absolute) && !isWindows()) throw new IllegalStateException(source + " points to a non-executable Java binary: " + absolute);
        return absolute;
    }

    private Path currentJavaExecutable() {
        return validateJavaExecutable(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"), "Maven JVM");
    }

    private boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    private String firstNonBlank(String first, String second) { if (first != null && !first.isBlank()) return first; return second == null || second.isBlank() ? null : second; }
    private String normalize(String value) { return value == null ? "surefire" : value.trim().toLowerCase(java.util.Locale.ROOT); }
}
