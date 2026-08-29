package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Models the Surefire/Failsafe fork-process settings ScenarioMesh can reproduce exactly.
 * Values remain structured so environment values never need to be logged or placed on a JVM command line.
 */
final class MavenForkLaunchConfiguration {
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    Analysis analyze(
            Plugin plugin,
            ProjectCompatibilityDetector.ExecutorKind executorKind,
            List<String> executionIds,
            Function<String, String> propertyResolver,
            Function<String, String> userPropertyResolver) {
        if (plugin == null) {
            MutableSettings defaults = new MutableSettings();
            applyUserOverrides(defaults, executorKind == ProjectCompatibilityDetector.ExecutorKind.FAILSAFE
                    ? "failsafe" : "surefire", new ArrayList<>(), userPropertyResolver);
            return Analysis.supported(Map.of("default-test", defaults.freeze()));
        }

        List<String> reasons = new ArrayList<>();
        Map<String, LaunchSettings> settingsByExecution = new LinkedHashMap<>();
        if (executorKind == ProjectCompatibilityDetector.ExecutorKind.SUREFIRE) {
            MutableSettings settings = new MutableSettings();
            inspect(plugin.getConfiguration(), "maven-surefire-plugin configuration", settings, reasons, propertyResolver);
            PluginExecution execution = findExecution(plugin, "default-test");
            if (execution != null) {
                inspect(execution.getConfiguration(), "maven-surefire-plugin execution 'default-test'",
                        settings, reasons, propertyResolver);
            }
            applyUserOverrides(settings, "surefire", reasons, userPropertyResolver);
            settingsByExecution.put("default-test", settings.freeze());
        } else {
            for (String executionId : executionIds) {
                MutableSettings settings = new MutableSettings();
                inspect(plugin.getConfiguration(), "maven-failsafe-plugin configuration", settings, reasons, propertyResolver);
                PluginExecution execution = findExecution(plugin, executionId);
                if (execution == null) {
                    reasons.add("maven-failsafe-plugin execution '" + executionId
                            + "' disappeared while resolving fork launch settings");
                    continue;
                }
                inspect(execution.getConfiguration(), "maven-failsafe-plugin execution '" + executionId + "'",
                        settings, reasons, propertyResolver);
                applyUserOverrides(settings, "failsafe", reasons, userPropertyResolver);
                settingsByExecution.put(executionId, settings.freeze());
            }
        }

        if (!reasons.isEmpty()) return Analysis.unsupported(String.join("; ", reasons));
        return Analysis.supported(settingsByExecution);
    }

    private void inspect(Object raw, String location, MutableSettings settings, List<String> reasons,
                         Function<String, String> propertyResolver) {
        if (!(raw instanceof Xpp3Dom configuration)) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!meaningful(child)) continue;
            switch (child.getName()) {
                case "enableAssertions" -> readEnableAssertions(child, location, settings, reasons, propertyResolver);
                case "environmentVariables" -> readEnvironment(child, location, settings, reasons, propertyResolver);
                case "excludedEnvironmentVariables" -> readExcludedEnvironment(child, location, settings, reasons, propertyResolver);
                case "workingDirectory" -> readWorkingDirectory(child, location, settings, reasons, propertyResolver);
                default -> { }
            }
        }
    }

    private void readEnableAssertions(Xpp3Dom node, String location, MutableSettings settings,
                                      List<String> reasons, Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <enableAssertions>; ScenarioMesh cannot prove Maven boolean binding");
            return;
        }
        String value = resolve(node.getValue(), location + " <enableAssertions>", reasons, propertyResolver);
        if (value == null) return;
        if ("true".equalsIgnoreCase(value.trim())) {
            settings.enableAssertions = true;
        } else if ("false".equalsIgnoreCase(value.trim())) {
            settings.enableAssertions = false;
        } else {
            reasons.add(location + " uses a non-boolean or blank <enableAssertions> value; ScenarioMesh will not guess Maven conversion semantics");
        }
    }

    private void readEnvironment(Xpp3Dom parent, String location, MutableSettings settings,
                                 List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (item.getChildCount() > 0 || item.getName() == null || item.getName().isBlank()) {
                reasons.add(location + " contains a structured or unnamed environment variable; fork environment cannot be reproduced exactly");
                continue;
            }
            String value = resolve(item.getValue(), location + " environment variable", reasons, propertyResolver);
            if (value != null) settings.environmentVariables.put(item.getName(), value);
        }
    }

    private void readExcludedEnvironment(Xpp3Dom parent, String location, MutableSettings settings,
                                         List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (item.getChildCount() > 0) {
                reasons.add(location + " contains structured <excludedEnvironmentVariables>; fork environment cannot be reproduced exactly");
                continue;
            }
            String value = resolve(item.getValue(), location + " <excludedEnvironmentVariables>", reasons, propertyResolver);
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                reasons.add(location + " contains an empty excluded environment variable name");
            } else {
                settings.excludedEnvironmentVariables.add(trimmed);
            }
        }
    }

    private void readWorkingDirectory(Xpp3Dom node, String location, MutableSettings settings,
                                      List<String> reasons, Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <workingDirectory>; fork working directory cannot be reproduced exactly");
            return;
        }
        String value = resolve(node.getValue(), location + " <workingDirectory>", reasons, propertyResolver);
        if (value == null || value.isBlank()) {
            reasons.add(location + " uses a blank <workingDirectory>; ScenarioMesh will not guess Maven File conversion semantics");
            return;
        }
        String baseDir = propertyResolver.apply("project.basedir");
        if (baseDir == null || baseDir.isBlank()) {
            reasons.add(location + " uses <workingDirectory> but Maven project.basedir is unavailable");
            return;
        }
        try {
            Path configured = Path.of(value.trim());
            Path base = Path.of(baseDir);
            settings.workingDirectory = (configured.isAbsolute() ? configured : base.resolve(configured))
                    .toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            reasons.add(location + " uses an invalid <workingDirectory> path; ScenarioMesh will pass through");
        }
    }

    private void applyUserOverrides(MutableSettings settings, String executorName, List<String> reasons,
                                    Function<String, String> userPropertyResolver) {
        String assertions = userPropertyResolver.apply("enableAssertions");
        if (assertions != null) {
            if ("true".equalsIgnoreCase(assertions.trim())) settings.enableAssertions = true;
            else if ("false".equalsIgnoreCase(assertions.trim())) settings.enableAssertions = false;
            else reasons.add("Maven user property 'enableAssertions' is non-boolean; ScenarioMesh will not guess Maven conversion semantics");
        }

        String basedirOverride = userPropertyResolver.apply("basedir");
        if (basedirOverride != null) {
            try {
                Path path = Path.of(basedirOverride.trim());
                if (!path.isAbsolute()) {
                    reasons.add("Maven user property 'basedir' is relative; ScenarioMesh will not guess Maven File converter resolution");
                } else settings.workingDirectory = path.toAbsolutePath().normalize();
            } catch (RuntimeException invalid) {
                reasons.add("Maven user property 'basedir' is not a valid path");
            }
        }

        String excluded = userPropertyResolver.apply(executorName + ".excludedEnvironmentVariables");
        if (excluded != null) {
            settings.excludedEnvironmentVariables.clear();
            for (String item : excluded.split(",", -1)) {
                String name = item.trim();
                if (name.isEmpty()) {
                    reasons.add("Maven user property '" + executorName
                            + ".excludedEnvironmentVariables' contains an empty environment variable name");
                } else settings.excludedEnvironmentVariables.add(name);
            }
        }
    }

    private PluginExecution findExecution(Plugin plugin, String id) {
        if (plugin.getExecutions() == null) return null;
        for (PluginExecution execution : plugin.getExecutions()) {
            if (id.equals(execution.getId())) return execution;
        }
        return null;
    }

    private String resolve(String raw, String location, List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = raw == null ? "" : raw.trim();
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) {
                reasons.add(location + " references an unresolved Maven property");
                return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private boolean meaningful(Xpp3Dom node) {
        return node.getValue() != null || node.getChildCount() > 0
                || (node.getAttributeNames() != null && node.getAttributeNames().length > 0);
    }

    record LaunchSettings(boolean enableAssertions,
                          Map<String, String> environmentVariables,
                          Set<String> excludedEnvironmentVariables,
                          Path workingDirectory) {
        LaunchSettings {
            environmentVariables = Map.copyOf(environmentVariables == null ? Map.of() : environmentVariables);
            excludedEnvironmentVariables = Set.copyOf(
                    excludedEnvironmentVariables == null ? Set.of() : excludedEnvironmentVariables);
        }

        static LaunchSettings defaults() {
            return new LaunchSettings(true, Map.of(), Set.of(), null);
        }

        boolean hasCustomProcessContext() {
            return !enableAssertions || !environmentVariables.isEmpty()
                    || !excludedEnvironmentVariables.isEmpty() || workingDirectory != null;
        }
    }

    record Analysis(boolean supported, String reason, Map<String, LaunchSettings> byExecutionId) {
        Analysis {
            byExecutionId = Map.copyOf(byExecutionId == null ? Map.of() : byExecutionId);
        }
        static Analysis supported(Map<String, LaunchSettings> values) {
            return new Analysis(true, null, values);
        }
        static Analysis unsupported(String reason) {
            return new Analysis(false, reason, Map.of());
        }
        LaunchSettings required(String executionId) {
            LaunchSettings value = byExecutionId.get(executionId);
            if (value == null) throw new IllegalStateException("Missing fork launch settings for Maven execution '" + executionId + "'");
            return value;
        }
    }

    private static final class MutableSettings {
        private boolean enableAssertions = true;
        private final Map<String, String> environmentVariables = new LinkedHashMap<>();
        private final Set<String> excludedEnvironmentVariables = new LinkedHashSet<>();
        private Path workingDirectory;

        LaunchSettings freeze() {
            return new LaunchSettings(enableAssertions, environmentVariables,
                    excludedEnvironmentVariables, workingDirectory);
        }
    }
}
