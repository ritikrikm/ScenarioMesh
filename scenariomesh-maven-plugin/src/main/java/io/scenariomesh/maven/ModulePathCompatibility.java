package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Mirrors the Surefire/Failsafe useModulePath decision sufficiently to fail closed. */
final class ModulePathCompatibility {
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    boolean nativeExecutorUsesModulePath(MavenProject project, MavenSession session, String executor) {
        if (!hasModuleDescriptor(project)) return false;
        boolean failsafe = "failsafe".equalsIgnoreCase(executor);
        String propertyName = failsafe ? "failsafe.useModulePath" : "surefire.useModulePath";
        Boolean commandLine = propertyBoolean(session, propertyName);
        if (commandLine != null) return commandLine;

        Plugin plugin = project.getPlugin(failsafe ? FAILSAFE : SUREFIRE);
        Boolean pluginValue = configuredBoolean(plugin == null ? null : plugin.getConfiguration(), project, session);
        boolean base = pluginValue == null || pluginValue;
        if (plugin == null || plugin.getExecutions() == null) return base;

        List<PluginExecution> relevant = relevantExecutions(plugin, failsafe);
        if (relevant.isEmpty()) return base;
        for (PluginExecution execution : relevant) {
            Boolean executionValue = configuredBoolean(execution.getConfiguration(), project, session);
            if (executionValue == null ? base : executionValue) return true;
        }
        return false;
    }

    private boolean hasModuleDescriptor(MavenProject project) {
        String main = project.getBuild().getOutputDirectory();
        String test = project.getBuild().getTestOutputDirectory();
        return (main != null && Files.isRegularFile(Path.of(main).resolve("module-info.class")))
                || (test != null && Files.isRegularFile(Path.of(test).resolve("module-info.class")));
    }

    private List<PluginExecution> relevantExecutions(Plugin plugin, boolean failsafe) {
        String goal = failsafe ? "integration-test" : "test";
        List<PluginExecution> result = new ArrayList<>();
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals() != null && execution.getGoals().contains(goal)) result.add(execution);
            else if (!failsafe && "default-test".equals(execution.getId())) result.add(execution);
        }
        return result;
    }

    private Boolean propertyBoolean(MavenSession session, String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) value = session.getSystemProperties().getProperty(key);
        return parse(value, key);
    }

    private Boolean configuredBoolean(Object rawConfiguration, MavenProject project, MavenSession session) {
        Xpp3Dom root = rawConfiguration instanceof Xpp3Dom dom ? dom : null;
        if (root == null) return null;
        Xpp3Dom node = root.getChild("useModulePath");
        if (node == null) return null;
        if (node.getChildCount() > 0) throw new IllegalStateException("Structured <useModulePath> is not reproducible");
        String raw = node.getValue();
        if (raw != null && raw.startsWith("${") && raw.endsWith("}")) {
            String key = raw.substring(2, raw.length() - 1);
            String resolved = session.getUserProperties().getProperty(key);
            if (resolved == null) resolved = session.getSystemProperties().getProperty(key);
            if (resolved == null && project.getProperties() != null) resolved = project.getProperties().getProperty(key);
            if (resolved == null) throw new IllegalStateException("Unresolved <useModulePath> property " + raw);
            raw = resolved;
        }
        return parse(raw, "useModulePath");
    }

    private Boolean parse(String raw, String location) {
        if (raw == null || raw.isBlank()) return null;
        if ("true".equalsIgnoreCase(raw.trim())) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw.trim())) return Boolean.FALSE;
        throw new IllegalStateException(location + " must resolve to true or false, but was '" + raw + "'");
    }
}
