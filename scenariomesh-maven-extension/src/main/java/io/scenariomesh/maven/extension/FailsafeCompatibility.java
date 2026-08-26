package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

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
 * Validates active Failsafe integration-test executions and translates each
 * execution into an independent ScenarioMesh execution plan. Distinct Maven
 * executions are never collapsed or deduplicated.
 */
final class FailsafeCompatibility {
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/IT*.java", "**/*IT.java", "**/*ITCase.java");
    private static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of("**/*$*");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern LATE_PROPERTY_REFERENCE = Pattern.compile("@\\{([^}]+)}");

    Analysis analyze(Plugin plugin,
                     MavenExecutionPlan.PluginParticipation participation,
                     Function<String, String> propertyResolver) {
        return analyze(plugin, participation, propertyResolver, propertyResolver);
    }

    Analysis analyze(Plugin plugin,
                     MavenExecutionPlan.PluginParticipation participation,
                     Function<String, String> propertyResolver,
                     Function<String, String> stableLatePropertyResolver) {
        if (plugin.getDependencies() != null && !plugin.getDependencies().isEmpty()) {
            return Analysis.unsupported(
                    "maven-failsafe-plugin declares custom provider/plugin dependencies; provider semantics are not yet reproducible");
        }

        List<PluginExecution> testExecutions = participation.activeExecutions().stream()
                .filter(this::containsIntegrationTestGoal)
                .toList();
        if (testExecutions.isEmpty()) {
            return Analysis.unsupported(
                    "Failsafe participates in the invocation but no active integration-test goal could be isolated");
        }

        List<ExecutionPlan> plans = new ArrayList<>();
        List<String> allReasons = new ArrayList<>();
        for (PluginExecution execution : testExecutions) {
            List<String> reasons = new ArrayList<>();
            EffectiveSettings settings = new EffectiveSettings();
            inspectConfiguration(plugin.getConfiguration(), "maven-failsafe-plugin configuration",
                    settings, reasons, propertyResolver, stableLatePropertyResolver);
            inspectConfiguration(execution.getConfiguration(),
                    "maven-failsafe-plugin execution '" + executionId(execution) + "'",
                    settings, reasons, propertyResolver, stableLatePropertyResolver);

            if (settings.rerunFailingTestsCount > 0) {
                reasons.add("rerunFailingTestsCount resolves to " + settings.rerunFailingTestsCount
                        + "; ScenarioMesh will not risk duplicating retries until exact Failsafe retry semantics are implemented");
            }
            if (!reasons.isEmpty()) {
                allReasons.add("execution '" + executionId(execution) + "': " + String.join("; ", reasons));
                continue;
            }
            if (settings.explicitlySkipped) {
                plans.add(ExecutionPlan.skipped(executionId(execution)));
                continue;
            }

            List<String> includes = settings.includes.isEmpty()
                    ? MavenClassNamePatterns.toRegexes(DEFAULT_INCLUDE_PATTERNS)
                    : MavenClassNamePatterns.toRegexes(List.copyOf(settings.includes));
            List<String> excludes = settings.excludes.isEmpty()
                    ? MavenClassNamePatterns.toRegexes(DEFAULT_EXCLUDE_PATTERNS)
                    : MavenClassNamePatterns.toRegexes(List.copyOf(settings.excludes));
            plans.add(new ExecutionPlan(
                    executionId(execution), false, includes, excludes,
                    List.copyOf(settings.jvmArgs), Map.copyOf(settings.systemProperties),
                    settings.testFailureIgnore));
        }

        if (!allReasons.isEmpty()) return Analysis.unsupported(String.join("; ", allReasons));
        if (plans.isEmpty()) return Analysis.unsupported("Failsafe execution analysis produced no reproducible execution plans");

        boolean allSkipped = plans.stream().allMatch(ExecutionPlan::explicitlySkipped);
        long activeCount = plans.stream().filter(plan -> !plan.explicitlySkipped()).count();
        String reason = allSkipped
                ? "all active Failsafe integration-test executions are explicitly skipped"
                : activeCount == 1
                    ? "one compatible Failsafe integration-test execution detected"
                    : activeCount + " compatible Failsafe integration-test executions modeled independently";
        return new Analysis(true, allSkipped, List.copyOf(plans), reason);
    }

    private boolean containsIntegrationTestGoal(PluginExecution execution) {
        return execution.getGoals() != null
                && execution.getGoals().stream().anyMatch(goal -> "integration-test".equals(trim(goal)));
    }

    private void inspectConfiguration(Object raw,
                                      String location,
                                      EffectiveSettings settings,
                                      List<String> reasons,
                                      Function<String, String> propertyResolver,
                                      Function<String, String> stableLatePropertyResolver) {
        if (!(raw instanceof Xpp3Dom configuration)) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!meaningful(child)) continue;
            String name = child.getName();
            ExecutorConfigurationSemantics.Classification classification =
                    ExecutorConfigurationSemantics.forFailsafe(name);

            switch (classification.kind()) {
                case REPLACED_BY_SCENARIOMESH -> { }
                case REQUIRES_CAPABILITY -> reasons.add(location + " uses <" + name
                        + "> which requires ScenarioMesh capability '" + classification.capability() + "'");
                case UNKNOWN -> reasons.add(location + " uses unsupported configuration <" + name + ">");
                case PRESERVED -> preserveSetting(child, location, settings, reasons,
                        propertyResolver, stableLatePropertyResolver);
            }
        }
    }

    private void preserveSetting(Xpp3Dom child,
                                 String location,
                                 EffectiveSettings settings,
                                 List<String> reasons,
                                 Function<String, String> propertyResolver,
                                 Function<String, String> stableLatePropertyResolver) {
        switch (child.getName()) {
            case "includes" -> readPatternList(child, settings.includes, location, reasons, propertyResolver);
            case "excludes" -> readPatternList(child, settings.excludes, location, reasons, propertyResolver);
            case "skip", "skipITs", "skipTests" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (Boolean.TRUE.equals(value)) settings.explicitlySkipped = true;
            }
            case "useModulePath" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (value != null && !Boolean.FALSE.equals(value)) reasons.add(location + " uses <useModulePath> with unsupported semantics");
            }
            case "argLine" -> readArgLine(child, location, settings, reasons, propertyResolver, stableLatePropertyResolver);
            case "systemPropertyVariables" -> readSystemProperties(child, location, settings, reasons, propertyResolver);
            case "testFailureIgnore" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (value != null) settings.testFailureIgnore = value;
            }
            case "rerunFailingTestsCount" -> {
                Integer value = resolvedNonNegativeInteger(child, location, reasons, propertyResolver);
                if (value != null) settings.rerunFailingTestsCount = value;
            }
            default -> reasons.add(location + " has no preservation implementation for <" + child.getName() + ">");
        }
    }

    private void readArgLine(Xpp3Dom node, String location, EffectiveSettings settings, List<String> reasons,
                             Function<String, String> propertyResolver,
                             Function<String, String> stableLatePropertyResolver) {
        if (node.getChildCount() > 0) { reasons.add(location + " contains a structured <argLine> that cannot be reproduced safely"); return; }
        String resolved = resolve(node.getValue(), location + " <argLine>", reasons, propertyResolver);
        if (resolved == null || resolved.isBlank()) { settings.jvmArgs.clear(); return; }
        resolved = resolveLate(resolved, location + " <argLine>", reasons, stableLatePropertyResolver);
        if (resolved == null) return;
        try {
            settings.jvmArgs.clear();
            settings.jvmArgs.addAll(List.of(CommandLineUtils.translateCommandline(resolved)));
        } catch (Exception exception) {
            reasons.add(location + " contains an <argLine> that cannot be tokenized safely: " + exception.getMessage());
        }
    }

    private String resolveLate(String value, String location, List<String> reasons,
                               Function<String, String> stableLatePropertyResolver) {
        Matcher matcher = LATE_PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = stableLatePropertyResolver.apply(key);
            if (replacement == null) {
                reasons.add(location + " uses late property replacement @{" + key + "}; its value is not fixed by stable Maven/process sources");
                return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private void readSystemProperties(Xpp3Dom parent, String location, EffectiveSettings settings,
                                      List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (property.getChildCount() > 0) { reasons.add(location + " contains nested system property '" + property.getName() + "'"); continue; }
            String value = resolve(property.getValue(), location + " system property '" + property.getName() + "'", reasons, propertyResolver);
            if (value != null) settings.systemProperties.put(property.getName(), value);
        }
    }

    private void readPatternList(Xpp3Dom parent, Set<String> destination, String location,
                                 List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"include".equals(item.getName()) && !"exclude".equals(item.getName())) {
                reasons.add(location + " contains unsupported <" + item.getName() + "> inside <" + parent.getName() + ">"); continue;
            }
            String value = resolve(item.getValue(), location + " <" + parent.getName() + ">", reasons, propertyResolver);
            if (value == null || value.isBlank()) reasons.add(location + " contains an empty class selection pattern in <" + parent.getName() + ">");
            else {
                try { MavenClassNamePatterns.toRegex(value); destination.add(value); }
                catch (IllegalArgumentException unsupportedPattern) {
                    reasons.add(location + " uses unsupported Maven class selection pattern '" + value + "': " + unsupportedPattern.getMessage());
                }
            }
        }
    }

    private Boolean resolvedBoolean(Xpp3Dom node, String location, List<String> reasons,
                                    Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) { reasons.add(location + " uses structured <" + node.getName() + "> and boolean semantics cannot be proven"); return null; }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null || value.isBlank()) return Boolean.FALSE;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        reasons.add(location + " uses non-boolean <" + node.getName() + "> value '" + value + "'"); return null;
    }

    private Integer resolvedNonNegativeInteger(Xpp3Dom node, String location, List<String> reasons,
                                               Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) { reasons.add(location + " uses structured <" + node.getName() + "> and integer semantics cannot be proven"); return null; }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null || value.isBlank()) return 0;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) { reasons.add(location + " uses negative <" + node.getName() + "> value '" + value + "'"); return null; }
            return parsed;
        } catch (NumberFormatException exception) {
            reasons.add(location + " uses non-integer <" + node.getName() + "> value '" + value + "'"); return null;
        }
    }

    private String resolve(String raw, String location, List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = trim(raw);
        if (value == null) return "";
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) { reasons.add(location + " references unresolved Maven property ${" + matcher.group(1) + "}"); return null; }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    static String mavenClassPatternToRegex(String pattern) { return MavenClassNamePatterns.toRegex(pattern); }
    private boolean meaningful(Xpp3Dom node) { return trim(node.getValue()) != null || node.getChildCount() > 0 || (node.getAttributeNames() != null && node.getAttributeNames().length > 0); }
    private String executionId(PluginExecution execution) { String id = trim(execution.getId()); return id == null ? "<unnamed>" : id; }
    private static String trim(String value) { if (value == null) return null; String t = value.trim(); return t.isEmpty() ? null : t; }

    record ExecutionPlan(String executionId, boolean explicitlySkipped, List<String> includeClassNameRegexes,
                         List<String> excludeClassNameRegexes, List<String> jvmArgs,
                         Map<String, String> systemProperties, boolean testFailureIgnore) {
        ExecutionPlan {
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs);
            systemProperties = Map.copyOf(systemProperties == null ? Map.of() : systemProperties);
        }
        static ExecutionPlan skipped(String executionId) { return new ExecutionPlan(executionId, true, List.of(), List.of(), List.of(), Map.of(), false); }
    }

    record Analysis(boolean supported, boolean explicitlySkipped, List<ExecutionPlan> executionPlans, String reason) {
        Analysis { executionPlans = List.copyOf(executionPlans == null ? List.of() : executionPlans); }
        static Analysis unsupported(String reason) { return new Analysis(false, false, List.of(), reason); }
    }

    private static final class EffectiveSettings {
        private final Set<String> includes = new LinkedHashSet<>();
        private final Set<String> excludes = new LinkedHashSet<>();
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private boolean explicitlySkipped;
        private boolean testFailureIgnore;
        private int rerunFailingTestsCount;
    }
}
