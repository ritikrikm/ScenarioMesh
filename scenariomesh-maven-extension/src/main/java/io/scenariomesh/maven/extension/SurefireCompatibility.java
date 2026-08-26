package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the effective Surefire model without confusing Maven's generated
 * default lifecycle execution with a user-defined custom execution.
 */
final class SurefireCompatibility {
    private static final String DEFAULT_TEST_EXECUTION_ID = "default-test";
    private static final String TEST_PHASE = "test";
    private static final String TEST_GOAL = "test";
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/Test*.java",
            "**/*Test.java",
            "**/*Tests.java",
            "**/*TestCase.java");

    Analysis analyze(Plugin surefire) {
        return analyze(surefire, ignored -> null);
    }

    Analysis analyze(Plugin surefire, Function<String, String> propertyResolver) {
        List<String> reasons = new ArrayList<>();
        boolean explicitlySkipsTests = false;

        List<Dependency> dependencies = surefire.getDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            reasons.add("maven-surefire-plugin declares custom provider/plugin dependencies");
        }

        ConfigurationAnalysis pluginConfiguration = analyzeConfiguration(
                surefire.getConfiguration(), "maven-surefire-plugin configuration", propertyResolver);
        explicitlySkipsTests |= pluginConfiguration.explicitlySkipsTests();
        reasons.addAll(pluginConfiguration.reasons());

        List<PluginExecution> executions = surefire.getExecutions();
        int standardLifecycleExecutions = 0;
        if (executions != null) {
            for (PluginExecution execution : executions) {
                if (!isStandardLifecycleExecution(execution)) {
                    reasons.add("maven-surefire-plugin declares non-standard execution '"
                            + executionId(execution) + "'");
                    continue;
                }

                standardLifecycleExecutions++;
                ConfigurationAnalysis executionConfiguration = analyzeConfiguration(
                        execution.getConfiguration(),
                        "maven-surefire-plugin execution '" + DEFAULT_TEST_EXECUTION_ID + "'",
                        propertyResolver);
                explicitlySkipsTests |= executionConfiguration.explicitlySkipsTests();
                reasons.addAll(executionConfiguration.reasons());
            }
        }

        if (standardLifecycleExecutions > 1) {
            reasons.add("maven-surefire-plugin exposes multiple default-test executions; "
                    + "ScenarioMesh cannot prove single-execution equivalence");
        }

        return new Analysis(
                explicitlySkipsTests,
                List.copyOf(reasons),
                defaultIncludeClassNameRegexes());
    }

    static List<String> defaultIncludeClassNameRegexes() {
        return MavenClassNamePatterns.toRegexes(DEFAULT_INCLUDE_PATTERNS);
    }

    private boolean isStandardLifecycleExecution(PluginExecution execution) {
        if (!DEFAULT_TEST_EXECUTION_ID.equals(trimToNull(execution.getId()))) return false;
        String phase = trimToNull(execution.getPhase());
        if (phase != null && !TEST_PHASE.equals(phase)) return false;
        List<String> goals = execution.getGoals();
        return goals != null && goals.size() == 1 && TEST_GOAL.equals(trimToNull(goals.get(0)));
    }

    private ConfigurationAnalysis analyzeConfiguration(Object rawConfiguration,
                                                       String location,
                                                       Function<String, String> propertyResolver) {
        Xpp3Dom configuration = asDom(rawConfiguration);
        if (configuration == null) return ConfigurationAnalysis.empty();

        List<String> reasons = new ArrayList<>();
        boolean explicitlySkipsTests = false;

        for (Xpp3Dom child : configuration.getChildren()) {
            if (!hasMeaningfulValue(child)) continue;

            String name = child.getName();
            ExecutorConfigurationSemantics.Classification classification =
                    ExecutorConfigurationSemantics.forSurefire(name);

            switch (classification.kind()) {
                case REPLACED_BY_SCENARIOMESH -> {
                    // forkCount/reuseForks/parallel/thread settings control Surefire's
                    // concurrency implementation. ScenarioMesh intentionally replaces
                    // that layer with its worker pool while preserving the selected tests.
                }
                case REQUIRES_CAPABILITY -> reasons.add(location + " uses <" + name
                        + "> which requires ScenarioMesh capability '" + classification.capability() + "'");
                case UNKNOWN -> reasons.add(location + " uses unsupported configuration <" + name + ">");
                case PRESERVED -> {
                    switch (name) {
                        case "skip", "skipTests" -> {
                            Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                            if (Boolean.TRUE.equals(value)) explicitlySkipsTests = true;
                        }
                        case "useModulePath" -> {
                            Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                            if (value != null && !Boolean.FALSE.equals(value)) {
                                reasons.add(location
                                        + " uses <useModulePath> with a value ScenarioMesh does not yet reproduce");
                            }
                        }
                        default -> reasons.add(location + " has no preservation implementation for <" + name + ">");
                    }
                }
            }
        }

        return new ConfigurationAnalysis(explicitlySkipsTests, List.copyOf(reasons));
    }

    private Boolean resolvedBoolean(Xpp3Dom node,
                                    String location,
                                    List<String> reasons,
                                    Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName()
                    + "> and boolean semantics cannot be proven");
            return null;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null) return null;
        if (value.isBlank()) return Boolean.FALSE;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        reasons.add(location + " uses non-boolean <" + node.getName() + "> value '" + value + "'");
        return null;
    }

    private String resolve(String raw,
                           String location,
                           List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = trimToNull(raw);
        if (value == null) return "";
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) {
                reasons.add(location + " references unresolved Maven property ${" + matcher.group(1) + "}");
                return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Xpp3Dom asDom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private boolean hasMeaningfulValue(Xpp3Dom node) {
        String value = trimToNull(node.getValue());
        String[] attributes = node.getAttributeNames();
        return value != null || node.getChildCount() > 0 || (attributes != null && attributes.length > 0);
    }

    private String executionId(PluginExecution execution) {
        String id = trimToNull(execution.getId());
        return id == null ? "<unnamed>" : id;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Analysis(
            boolean explicitlySkipsTests,
            List<String> reasons,
            List<String> includeClassNameRegexes) {
        Analysis {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            includeClassNameRegexes = List.copyOf(
                    includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
        }
    }

    private record ConfigurationAnalysis(boolean explicitlySkipsTests, List<String> reasons) {
        private static ConfigurationAnalysis empty() {
            return new ConfigurationAnalysis(false, List.of());
        }
    }
}
