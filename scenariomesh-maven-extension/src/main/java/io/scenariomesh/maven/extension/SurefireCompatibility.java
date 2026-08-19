package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies the effective Surefire model without confusing Maven's generated
 * default lifecycle execution with a user-defined custom execution.
 *
 * <p>The compatibility policy is intentionally conservative. ScenarioMesh only
 * accepts the well-known default Surefire test execution plus configuration it
 * has explicitly reviewed as semantically safe. Unknown or newly introduced
 * configuration is treated as pass-through material until ScenarioMesh learns
 * how to reproduce it. That makes Maven/Surefire evolution fail safe rather
 * than silently changing what a user's normal {@code mvn test} would do.</p>
 */
final class SurefireCompatibility {
    private static final String DEFAULT_TEST_EXECUTION_ID = "default-test";
    private static final String TEST_PHASE = "test";
    private static final String TEST_GOAL = "test";

    Analysis analyze(Plugin surefire) {
        List<String> reasons = new ArrayList<>();
        boolean explicitlySkipsTests = false;

        List<Dependency> dependencies = surefire.getDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            reasons.add("maven-surefire-plugin declares custom provider/plugin dependencies");
        }

        ConfigurationAnalysis pluginConfiguration = analyzeConfiguration(
                surefire.getConfiguration(), "maven-surefire-plugin configuration");
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
                        "maven-surefire-plugin execution '" + DEFAULT_TEST_EXECUTION_ID + "'");
                explicitlySkipsTests |= executionConfiguration.explicitlySkipsTests();
                reasons.addAll(executionConfiguration.reasons());
            }
        }

        if (standardLifecycleExecutions > 1) {
            reasons.add("maven-surefire-plugin exposes multiple default-test executions; "
                    + "ScenarioMesh cannot prove single-execution equivalence");
        }

        return new Analysis(explicitlySkipsTests, List.copyOf(reasons));
    }

    /**
     * Maven's effective model may contain a lifecycle-generated execution even
     * when the user's POM has no explicit {@code <executions>} block. Only the
     * canonical Surefire test lifecycle shape is ignored here. Any different
     * id, phase, or goal remains conservatively unsupported.
     */
    private boolean isStandardLifecycleExecution(PluginExecution execution) {
        if (!DEFAULT_TEST_EXECUTION_ID.equals(trimToNull(execution.getId()))) {
            return false;
        }

        String phase = trimToNull(execution.getPhase());
        if (phase != null && !TEST_PHASE.equals(phase)) {
            return false;
        }

        List<String> goals = execution.getGoals();
        return goals != null
                && goals.size() == 1
                && TEST_GOAL.equals(trimToNull(goals.get(0)));
    }

    private ConfigurationAnalysis analyzeConfiguration(Object rawConfiguration, String location) {
        Xpp3Dom configuration = asDom(rawConfiguration);
        if (configuration == null) {
            return ConfigurationAnalysis.empty();
        }

        List<String> reasons = new ArrayList<>();
        boolean explicitlySkipsTests = false;

        for (Xpp3Dom child : configuration.getChildren()) {
            if (!hasMeaningfulValue(child)) {
                continue;
            }

            String name = child.getName();
            switch (name) {
                case "skip", "skipTests" -> {
                    Boolean value = literalBoolean(child);
                    if (Boolean.TRUE.equals(value)) {
                        explicitlySkipsTests = true;
                    } else if (value == null) {
                        reasons.add(location + " uses non-literal <" + name
                                + ">; ScenarioMesh cannot prove Maven-equivalent skip semantics");
                    }
                }
                case "useModulePath" -> {
                    Boolean value = literalBoolean(child);
                    if (!Boolean.FALSE.equals(value)) {
                        reasons.add(location
                                + " uses <useModulePath> with a value ScenarioMesh does not yet reproduce");
                    }
                }
                default -> reasons.add(location + " uses unsupported configuration <" + name + ">");
            }
        }

        return new ConfigurationAnalysis(explicitlySkipsTests, List.copyOf(reasons));
    }

    private Xpp3Dom asDom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private Boolean literalBoolean(Xpp3Dom node) {
        if (node.getChildCount() > 0) {
            return null;
        }
        String value = trimToNull(node.getValue());
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private boolean hasMeaningfulValue(Xpp3Dom node) {
        String value = trimToNull(node.getValue());
        String[] attributes = node.getAttributeNames();
        return value != null
                || node.getChildCount() > 0
                || (attributes != null && attributes.length > 0);
    }

    private String executionId(PluginExecution execution) {
        String id = trimToNull(execution.getId());
        return id == null ? "<unnamed>" : id;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Analysis(boolean explicitlySkipsTests, List<String> reasons) {
        Analysis {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    private record ConfigurationAnalysis(boolean explicitlySkipsTests, List<String> reasons) {
        private static ConfigurationAnalysis empty() {
            return new ConfigurationAnalysis(false, List.of());
        }
    }
}
