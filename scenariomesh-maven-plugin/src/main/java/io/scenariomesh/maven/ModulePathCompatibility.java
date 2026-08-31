package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mirrors the Surefire/Failsafe useModulePath decision and produces a worker JVM launch plan.
 * Unsupported/ambiguous module layouts fail closed rather than being silently flattened to a classpath.
 */
final class ModulePathCompatibility {
    static final String TARGET_MODULE_PATH_PROPERTY = "scenariomesh.target.modulePath";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    boolean nativeExecutorUsesModulePath(MavenProject project, MavenSession session, String executor) {
        return effectiveUseModulePath(project, session, executor);
    }

    LaunchPlan launchPlan(MavenProject project, MavenSession session, String executor, List<Path> targetClasspath) {
        if (!effectiveUseModulePath(project, session, executor)) return LaunchPlan.classpath();
        Path mainOutput = normalized(project.getBuild().getOutputDirectory());
        Path testOutput = normalized(project.getBuild().getTestOutputDirectory());
        boolean mainModule = hasDescriptor(mainOutput);
        boolean testModule = hasDescriptor(testOutput);
        if (!mainModule && !testModule) return LaunchPlan.classpath();

        List<Path> modulePath = targetClasspath == null ? List.of() : targetClasspath.stream()
                .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        if (modulePath.isEmpty()) {
            throw new IllegalStateException("JPMS execution is active but the effective target module path is empty");
        }

        List<String> args = new ArrayList<>();
        args.add("--module-path");
        args.add(join(modulePath));

        if (mainModule && !testModule) {
            String moduleName = moduleName(mainOutput);
            if (testOutput == null || !Files.isDirectory(testOutput)) {
                throw new IllegalStateException("JPMS main module '" + moduleName
                        + "' requires compiled test output for Surefire-style --patch-module execution");
            }
            args.add("--patch-module");
            args.add(moduleName + "=" + testOutput);
            args.add("--add-modules");
            args.add(moduleName);
        } else {
            if (mainModule && testModule) {
                String mainName = moduleName(mainOutput);
                String testName = moduleName(testOutput);
                if (mainName.equals(testName)) {
                    throw new IllegalStateException("main and test outputs expose the same JPMS module '" + mainName
                            + "'; duplicate descriptors require an explicit patch-module model");
                }
            }
            args.add("--add-modules");
            args.add("ALL-MODULE-PATH");
        }
        return new LaunchPlan(true, List.copyOf(args));
    }

    private boolean effectiveUseModulePath(MavenProject project, MavenSession session, String executor) {
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
        Set<Boolean> values = new LinkedHashSet<>();
        for (PluginExecution execution : relevant) {
            Boolean executionValue = configuredBoolean(execution.getConfiguration(), project, session);
            values.add(executionValue == null ? base : executionValue);
        }
        if (values.size() > 1) {
            throw new IllegalStateException("Maven test executions disagree on <useModulePath>; execution-scoped module launch "
                    + "must remain native until the differing module plans are injected independently");
        }
        return values.iterator().next();
    }

    private boolean hasModuleDescriptor(MavenProject project) {
        return hasDescriptor(normalized(project.getBuild().getOutputDirectory()))
                || hasDescriptor(normalized(project.getBuild().getTestOutputDirectory()));
    }

    private Path normalized(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private boolean hasDescriptor(Path output) {
        return output != null && Files.isRegularFile(output.resolve("module-info.class"));
    }

    private String moduleName(Path output) {
        try {
            Set<ModuleReference> modules = ModuleFinder.of(output).findAll();
            if (modules.size() != 1) {
                throw new IllegalStateException("expected exactly one JPMS module in " + output + " but found " + modules.size());
            }
            return modules.iterator().next().descriptor().name();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("cannot determine JPMS module name from " + output + ": "
                    + message(exception), exception);
        }
    }

    private String join(List<Path> paths) {
        return paths.stream().map(Path::toString)
                .reduce((left, right) -> left + File.pathSeparator + right).orElse("");
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

    private String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    record LaunchPlan(boolean modulePath, List<String> jvmArgs) {
        LaunchPlan { jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs); }
        static LaunchPlan classpath() { return new LaunchPlan(false, List.of()); }
    }
}
