package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative fallback for Surefire shapes the legacy single-execution detector intentionally rejects.
 * Each active Maven test execution is analyzed through the existing exact Surefire semantic analyzer.
 */
final class AdvancedSurefireCompatibilityDetector {
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final Set<String> MULTI_EXECUTION_PREFLIGHT_SAFE = Set.of(
            "includes", "excludes", "includesFile", "excludesFile", "dependenciesToScan",
            "skip", "skipTests", "testFailureIgnore", "failIfNoTests", "failIfNoSpecifiedTests",
            "runOrder", "runOrderRandomSeed", "runOrderStatisticsFileChecksum",
            "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
            "threadCountMethods", "threadCountSuites", "perCoreThreadCount",
            "useUnlimitedThreads", "parallelOptimized");
    private final SurefireCompatibility compatibility = new SurefireCompatibility();

    ProjectCompatibilityDetector.CompatibilityDecision evaluate(MavenSession session, MavenProject project) {
        Plugin plugin = project.getPlugin(SUREFIRE);
        if (plugin == null) return ProjectCompatibilityDetector.CompatibilityDecision.passThrough("no Surefire plugin model is available");
        MavenExecutionPlan lifecycle = MavenExecutionPlan.from(session).orElse(null);
        if (lifecycle == null || !lifecycle.reaches("test")) {
            return ProjectCompatibilityDetector.CompatibilityDecision.passThrough("requested Maven lifecycle does not reach test");
        }
        Plugin failsafe = project.getPlugin("org.apache.maven.plugins:maven-failsafe-plugin");
        if (failsafe != null && lifecycle.failsafeParticipation(failsafe).state() == MavenExecutionPlan.ParticipationState.ACTIVE) {
            return ProjectCompatibilityDetector.CompatibilityDecision.passThrough(
                    "Failsafe also participates; advanced Surefire fallback will not collapse mixed executor ownership");
        }

        List<PluginExecution> executions = activeTestExecutions(plugin);
        if (executions.isEmpty()) {
            return ProjectCompatibilityDetector.CompatibilityDecision.passThrough("no active Surefire test execution could be isolated");
        }
        if (executions.size() > 1) {
            String unsafe = firstPreflightCriticalSetting(plugin, executions);
            if (unsafe != null) {
                return ProjectCompatibilityDetector.CompatibilityDecision.passThrough(
                        "multiple Surefire executions use preflight-critical configuration <" + unsafe
                                + ">; ScenarioMesh will not suppress all executions after proving only one process/discovery context");
            }
        }

        EffectivePropertyResolver properties = new EffectivePropertyResolver(session, project);
        CommandLineClassSelection.Analysis commandSelection = CommandLineClassSelection.analyze(
                "Surefire", "test", properties.userProperty("test"));
        if (!commandSelection.supported()) {
            return ProjectCompatibilityDetector.CompatibilityDecision.passThrough(commandSelection.reason());
        }

        List<ProjectCompatibilityDetector.ExecutorPlan> plans = new ArrayList<>();
        for (PluginExecution execution : executions) {
            Plugin synthetic = synthetic(plugin, execution);
            SurefireCompatibility.Analysis analysis = compatibility.analyze(
                    synthetic, properties::resolve, session.getUserProperties());
            if (!analysis.reasons().isEmpty()) {
                return ProjectCompatibilityDetector.CompatibilityDecision.passThrough(
                        "Surefire execution '" + executionId(execution) + "' is not reproducible: "
                                + String.join("; ", analysis.reasons()));
            }
            if (analysis.explicitlySkipsTests()) continue;
            if (analysis.systemProperties().containsKey("groups") || analysis.systemProperties().containsKey("excludedGroups")) {
                return ProjectCompatibilityDetector.CompatibilityDecision.passThrough(
                        "multiple Surefire executions with groups/excludedGroups remain native until provider-specific group semantics are proven per execution");
            }

            List<String> includes = analysis.includeClassNameRegexes();
            List<String> excludes = analysis.excludeClassNameRegexes();
            Map<String, String> systemProperties = new LinkedHashMap<>(analysis.systemProperties());
            if (commandSelection.present()) {
                includes = commandSelection.includeRegexes();
                excludes = List.of();
                systemProperties.remove(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS);
                systemProperties.remove(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS);
                if (commandSelection.testListExpression() != null) {
                    systemProperties.put(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION,
                            commandSelection.testListExpression());
                }
            }
            if (analysis.argLine() != null && !analysis.argLine().isBlank()) {
                systemProperties.put(RuntimePropertyNames.MAVEN_EXECUTOR_ARG_LINE, analysis.argLine());
            }
            systemProperties.put(RuntimePropertyNames.MAVEN_ZERO_TEST_POLICY_ENABLED, "true");
            systemProperties.put(RuntimePropertyNames.MAVEN_FAIL_IF_NO_TESTS, Boolean.toString(analysis.failIfNoTests()));
            systemProperties.put(RuntimePropertyNames.MAVEN_FAIL_IF_NO_SPECIFIED_TESTS,
                    Boolean.toString(analysis.failIfNoSpecifiedTests()));
            systemProperties.put(RuntimePropertyNames.MAVEN_EXPLICIT_TEST_SELECTION,
                    Boolean.toString(commandSelection.present()));
            systemProperties.put(RuntimePropertyNames.MAVEN_PROMOTE_USER_PROPERTIES,
                    Boolean.toString(analysis.promoteUserPropertiesToSystemProperties()));

            plans.add(new ProjectCompatibilityDetector.ExecutorPlan(
                    executionId(execution), includes, excludes, List.of(), Map.copyOf(systemProperties),
                    analysis.testFailureIgnore(), analysis.dependenciesToScan()));
        }
        if (plans.isEmpty()) {
            return ProjectCompatibilityDetector.CompatibilityDecision.passThrough("all active Surefire executions explicitly skip tests");
        }
        return ProjectCompatibilityDetector.CompatibilityDecision.takeOver(
                frameworkSignals(project), ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                "test", false, plans);
    }

    private String firstPreflightCriticalSetting(Plugin plugin, List<PluginExecution> executions) {
        String unsafe = firstUnsafeConfigurationName(plugin.getConfiguration());
        if (unsafe != null) return unsafe;
        for (PluginExecution execution : executions) {
            unsafe = firstUnsafeConfigurationName(execution.getConfiguration());
            if (unsafe != null) return unsafe;
        }
        return null;
    }

    private String firstUnsafeConfigurationName(Object raw) {
        if (!(raw instanceof Xpp3Dom configuration)) return null;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!hasMeaningfulValue(child)) continue;
            if (!MULTI_EXECUTION_PREFLIGHT_SAFE.contains(child.getName())) return child.getName();
        }
        return null;
    }

    private boolean hasMeaningfulValue(Xpp3Dom node) {
        return node.getValue() != null || node.getChildCount() > 0
                || (node.getAttributeNames() != null && node.getAttributeNames().length > 0);
    }

    private List<PluginExecution> activeTestExecutions(Plugin plugin) {
        List<PluginExecution> active = new ArrayList<>();
        if (plugin.getExecutions() == null) return active;
        for (PluginExecution execution : plugin.getExecutions()) {
            String phase = trim(execution.getPhase());
            boolean testGoal = execution.getGoals() != null
                    && execution.getGoals().stream().anyMatch(goal -> "test".equals(trim(goal)));
            if (!testGoal) continue;
            // Maven 4 phase indices (for example test[100]) affect ordering relative to other executions.
            // Do not flatten them into an ordinary test phase until that ordering contract is modeled.
            if (phase.isEmpty() || "test".equals(phase)) active.add(execution);
        }
        return List.copyOf(active);
    }

    private Plugin synthetic(Plugin original, PluginExecution execution) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(original.getGroupId());
        plugin.setArtifactId(original.getArtifactId());
        plugin.setVersion(original.getVersion());
        plugin.setConfiguration(original.getConfiguration());
        PluginExecution standard = new PluginExecution();
        standard.setId("default-test");
        standard.setPhase("test");
        standard.addGoal("test");
        standard.setConfiguration(execution.getConfiguration());
        plugin.addExecution(standard);
        // Dependencies are validated separately by MavenProviderDependencyCompatibility.
        return plugin;
    }

    private Set<String> frameworkSignals(MavenProject project) {
        Set<String> coordinates = new LinkedHashSet<>();
        if (project.getArtifacts() != null) {
            project.getArtifacts().forEach(a -> coordinates.add(a.getGroupId() + ":" + a.getArtifactId()));
        }
        Set<String> values = new LinkedHashSet<>();
        if (coordinates.stream().anyMatch(c -> c.startsWith("org.junit.jupiter:") || c.startsWith("org.junit.platform:"))) {
            values.add("junit-platform");
        }
        if (coordinates.contains("io.cucumber:cucumber-junit-platform-engine")) values.add("junit-platform");
        if (coordinates.contains("org.testng:testng")) values.add("testng");
        return Set.copyOf(values);
    }

    private String executionId(PluginExecution execution) {
        String id = execution.getId();
        return id == null || id.isBlank() ? "<unnamed>" : id;
    }

    private String trim(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }
}
