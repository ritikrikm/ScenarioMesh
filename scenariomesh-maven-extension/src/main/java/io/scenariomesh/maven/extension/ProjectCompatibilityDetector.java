package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Conservative compatibility gate for transparent Maven takeover. */
final class ProjectCompatibilityDetector {
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    private static final Set<String> TEST_LIFECYCLE_PHASES = Set.of(
            "test", "prepare-package", "package", "pre-integration-test",
            "integration-test", "post-integration-test", "verify", "install", "deploy");
    private static final Set<String> SUREFIRE_UNSAFE_SELECTION_PROPERTIES = Set.of(
            "dependenciesToScan");
    private static final Set<String> FAILSAFE_UNSAFE_SELECTION_PROPERTIES = Set.of(
            "suiteXmlFiles", "dependenciesToScan");
    private static final Set<String> CUCUMBER_SELECTION_PROPERTIES = Set.of(
            "cucumber.filter.tags", "cucumber.filter.name", "cucumber.features");
    private static final List<String> FRAMEWORK_PROPERTY_PREFIXES = List.of(
            "cucumber.", "junit.", "testng.");
    private static final Set<String> EXECUTOR_FRAMEWORK_PROPERTIES = Set.of("groups", "excludedGroups");

    private final SurefireCompatibility surefireCompatibility = new SurefireCompatibility();
    private final FailsafeCompatibility failsafeCompatibility = new FailsafeCompatibility();

    CompatibilityDecision evaluate(MavenSession session, MavenProject project) {
        EffectivePropertyResolver properties = new EffectivePropertyResolver(session, project);
        if (!requestsTestLifecycle(session)) return CompatibilityDecision.passThrough("requested Maven goals do not reach the test lifecycle");
        if (projectSkipsTests(properties)) return CompatibilityDecision.passThrough("effective Maven configuration explicitly skips tests");

        FrameworkSignals frameworks = detectFrameworks(project);
        if (frameworks.directJUnit4() && !frameworks.cucumberJUnit4()) {
            return CompatibilityDecision.passThrough(
                    "generic JUnit 4 is present, but the current product only supports JUnit 4 through the Cucumber JUnit 4 adapter");
        }

        if (frameworks.cucumberJUnit4() || frameworks.junitPlatform()) {
            String projectOnlySelection = firstProjectOnlyProperty(properties, CUCUMBER_SELECTION_PROPERTIES);
            if (projectOnlySelection != null) {
                return CompatibilityDecision.passThrough(
                        "Cucumber selection property '" + projectOnlySelection + "' is defined only as a Maven project property in the effective model; "
                                + "ScenarioMesh cannot prove that native Surefire/Failsafe would expose it to the test JVM. "
                                + "Pass it with -D or through the executor's systemPropertyVariables to enable safe takeover.");
            }
        }

        Map<String, String> frameworkSystemProperties = effectiveFrameworkSystemProperties(session, properties);
        Optional<MavenExecutionPlan> executionPlan = MavenExecutionPlan.from(session);
        if (executionPlan.isEmpty()) return CompatibilityDecision.passThrough("requested Maven lifecycle could not be determined safely");

        Plugin failsafe = plugin(project, FAILSAFE);
        MavenExecutionPlan.PluginParticipation failsafeParticipation = executionPlan.get().failsafeParticipation(failsafe);
        if (failsafeParticipation.state() == MavenExecutionPlan.ParticipationState.UNKNOWN) {
            return CompatibilityDecision.passThrough(
                    "maven-failsafe-plugin participation cannot be proven safe for Maven phase '"
                            + executionPlan.get().terminalPhase() + "' ("
                            + String.join(", ", failsafeParticipation.evidence()) + ")");
        }
        if (failsafeParticipation.state() == MavenExecutionPlan.ParticipationState.ACTIVE) {
            FailsafeCompatibility.Analysis analysis = failsafeCompatibility.analyze(
                    failsafe,
                    failsafeParticipation,
                    properties::resolve,
                    properties::resolveStableLate);
            if (!analysis.supported()) {
                return CompatibilityDecision.passThrough(
                        "maven-failsafe-plugin participates in this invocation but ScenarioMesh cannot reproduce it safely: "
                                + analysis.reason());
            }
            if (!analysis.explicitlySkipped()) {
                String unsafe = firstPresentProperty(properties, FAILSAFE_UNSAFE_SELECTION_PROPERTIES);
                if (unsafe != null) {
                    return CompatibilityDecision.passThrough(
                            "Failsafe test-selection property '" + unsafe
                                    + "' is present and is not yet reproduced by ScenarioMesh discovery");
                }

                CommandLineClassSelection.Analysis commandSelection = CommandLineClassSelection.analyze(
                        "Failsafe", "it.test", properties.userProperty("it.test"));
                if (!commandSelection.supported()) {
                    return CompatibilityDecision.passThrough(commandSelection.reason());
                }

                SelectionOverride selectionOverride;
                if (commandSelection.present()) {
                    selectionOverride = SelectionOverride.supported(commandSelection.includeRegexes(), List.of());
                } else {
                    selectionOverride = selectionOverride(
                            properties, "failsafe.includes", "failsafe.excludes", "Failsafe");
                }
                if (!selectionOverride.supported()) {
                    return CompatibilityDecision.passThrough(selectionOverride.reason());
                }
                List<ExecutorPlan> plans = analysis.executionPlans().stream()
                        .filter(plan -> !plan.explicitlySkipped())
                        .map(plan -> {
                            Map<String, String> planProperties = new LinkedHashMap<>(plan.systemProperties());
                            planProperties.putAll(frameworkSystemProperties);
                            attachAdvancedSelection(planProperties, commandSelection);
                            return new ExecutorPlan(
                                    plan.executionId(),
                                    selectionOverride.includes() == null
                                            ? plan.includeClassNameRegexes() : selectionOverride.includes(),
                                    selectionOverride.excludes() == null
                                            ? plan.excludeClassNameRegexes() : selectionOverride.excludes(),
                                    plan.jvmArgs(),
                                    planProperties,
                                    plan.testFailureIgnore());
                        })
                        .toList();
                if (!plans.isEmpty()) {
                    return CompatibilityDecision.takeOver(
                            frameworks.names(), ExecutorKind.FAILSAFE, "integration-test", true, plans);
                }
            }
        }

        List<String> reasons = new ArrayList<>();
        Plugin surefire = plugin(project, SUREFIRE);
        SurefireCompatibility.Analysis surefireAnalysis = null;
        if (surefire != null) {
            surefireAnalysis = surefireCompatibility.analyze(surefire, properties::resolve);
            if (surefireAnalysis.explicitlySkipsTests()) return CompatibilityDecision.passThrough("maven-surefire-plugin explicitly skips tests");
            reasons.addAll(surefireAnalysis.reasons());
        }
        String unsafe = firstPresentProperty(properties, SUREFIRE_UNSAFE_SELECTION_PROPERTIES);
        if (unsafe != null) reasons.add("Maven test-selection property '" + unsafe + "' is present and is not yet reproduced by ScenarioMesh discovery");
        if (!reasons.isEmpty()) return CompatibilityDecision.passThrough(String.join("; ", reasons));

        CommandLineClassSelection.Analysis commandSelection = CommandLineClassSelection.analyze(
                "Surefire", "test", properties.userProperty("test"));
        if (!commandSelection.supported()) {
            return CompatibilityDecision.passThrough(commandSelection.reason());
        }

        SelectionOverride selectionOverride;
        if (commandSelection.present()) {
            selectionOverride = SelectionOverride.supported(commandSelection.includeRegexes(), List.of());
        } else {
            selectionOverride = selectionOverride(
                    properties, "surefire.includes", "surefire.excludes", "Surefire");
        }
        if (!selectionOverride.supported()) return CompatibilityDecision.passThrough(selectionOverride.reason());

        List<String> includes = selectionOverride.includes() != null
                ? selectionOverride.includes()
                : surefireAnalysis == null
                    ? SurefireCompatibility.defaultIncludeClassNameRegexes()
                    : surefireAnalysis.includeClassNameRegexes();
        List<String> excludes = selectionOverride.excludes() != null
                ? selectionOverride.excludes()
                : surefireAnalysis == null
                    ? SurefireCompatibility.defaultExcludeClassNameRegexes()
                    : surefireAnalysis.excludeClassNameRegexes();
        Map<String, String> surefireSystemProperties = new LinkedHashMap<>();
        if (surefireAnalysis != null) surefireSystemProperties.putAll(surefireAnalysis.systemProperties());
        surefireSystemProperties.putAll(frameworkSystemProperties);
        attachAdvancedSelection(surefireSystemProperties, commandSelection);
        return CompatibilityDecision.takeOver(
                frameworks.names(), ExecutorKind.SUREFIRE, "test", false,
                List.of(new ExecutorPlan("default-test", includes, excludes, List.of(), surefireSystemProperties, false)));
    }

    private void attachAdvancedSelection(Map<String, String> properties,
                                         CommandLineClassSelection.Analysis selection) {
        if (selection != null && selection.testListExpression() != null) {
            properties.put(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION, selection.testListExpression());
        }
    }

    private SelectionOverride selectionOverride(EffectivePropertyResolver properties,
                                                String includesKey,
                                                String excludesKey,
                                                String executorName) {
        List<String> includes = null;
        List<String> excludes = null;
        if (properties.present(includesKey)) {
            String value = properties.resolve(includesKey);
            if (value == null || value.isBlank()) {
                return SelectionOverride.unsupported(executorName + " selection property '" + includesKey
                        + "' is blank; ScenarioMesh will not guess Maven's collection binding semantics");
            }
            try {
                includes = MavenClassNamePatterns.toRegexes(List.of(value));
            } catch (IllegalArgumentException unsupported) {
                return SelectionOverride.unsupported(executorName + " selection property '" + includesKey
                        + "' is not in ScenarioMesh's proven Maven selector subset: " + unsupported.getMessage());
            }
        }
        if (properties.present(excludesKey)) {
            String value = properties.resolve(excludesKey);
            if (value == null || value.isBlank()) {
                return SelectionOverride.unsupported(executorName + " selection property '" + excludesKey
                        + "' is blank; ScenarioMesh will not guess Maven's collection binding semantics");
            }
            try {
                excludes = MavenClassNamePatterns.toRegexes(List.of(value));
            } catch (IllegalArgumentException unsupported) {
                return SelectionOverride.unsupported(executorName + " selection property '" + excludesKey
                        + "' is not in ScenarioMesh's proven Maven selector subset: " + unsupported.getMessage());
            }
        }
        return SelectionOverride.supported(includes, excludes);
    }

    private Map<String, String> effectiveFrameworkSystemProperties(
            MavenSession session, EffectivePropertyResolver effectiveProperties) {
        Map<String, String> values = new LinkedHashMap<>();
        copyFrameworkProperties(session.getSystemProperties(), values);
        copyFrameworkProperties(session.getUserProperties(), values);
        for (String key : EXECUTOR_FRAMEWORK_PROPERTIES) {
            String value = effectiveProperties.resolve(key);
            if (value != null && !value.isBlank()) values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private void copyFrameworkProperties(java.util.Properties source, Map<String, String> target) {
        if (source == null) return;
        source.forEach((rawKey, rawValue) -> {
            String key = String.valueOf(rawKey);
            if (FRAMEWORK_PROPERTY_PREFIXES.stream().anyMatch(key::startsWith)) target.put(key, String.valueOf(rawValue));
        });
    }

    private String firstProjectOnlyProperty(EffectivePropertyResolver properties, Set<String> keys) {
        for (String key : keys) if (properties.projectOnly(key)) return key;
        return null;
    }

    private boolean requestsTestLifecycle(MavenSession session) {
        List<String> goals = session.getGoals();
        if (goals == null || goals.isEmpty()) return false;
        for (String raw : goals) {
            String goal = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (TEST_LIFECYCLE_PHASES.contains(goal) || goal.endsWith(":test") || goal.endsWith(":verify")) return true;
        }
        return false;
    }

    private boolean projectSkipsTests(EffectivePropertyResolver properties) {
        return booleanProperty(properties, "skipTests") || booleanProperty(properties, "maven.test.skip");
    }

    private boolean booleanProperty(EffectivePropertyResolver properties, String key) {
        String value = properties.resolve(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private String firstPresentProperty(EffectivePropertyResolver properties, Set<String> keys) {
        for (String key : keys) if (properties.present(key)) return key;
        return null;
    }

    private FrameworkSignals detectFrameworks(MavenProject project) {
        Set<String> coordinates = new LinkedHashSet<>();
        if (project.getDependencies() != null) for (Dependency dependency : project.getDependencies()) coordinates.add(coordinate(dependency.getGroupId(), dependency.getArtifactId()));
        if (project.getArtifacts() != null) for (Artifact artifact : project.getArtifacts()) coordinates.add(coordinate(artifact.getGroupId(), artifact.getArtifactId()));
        boolean cucumberPlatform = coordinates.contains("io.cucumber:cucumber-junit-platform-engine");
        boolean cucumberJUnit4 = coordinates.contains("io.cucumber:cucumber-junit") || coordinates.contains("info.cukes:cucumber-junit");
        boolean testNg = coordinates.contains("org.testng:testng");
        boolean junit5 = coordinates.stream().anyMatch(c -> c.startsWith("org.junit.jupiter:"))
                || coordinates.contains("org.junit.platform:junit-platform-engine")
                || coordinates.contains("org.junit.platform:junit-platform-launcher")
                || coordinates.contains("org.junit.platform:junit-platform-suite-engine");
        boolean directJUnit4 = hasDirectDependency(project, "junit", "junit");
        return new FrameworkSignals(junit5 || cucumberPlatform, cucumberJUnit4, testNg, directJUnit4);
    }

    private boolean hasDirectDependency(MavenProject project, String groupId, String artifactId) {
        if (project.getDependencies() == null) return false;
        for (Dependency dependency : project.getDependencies()) if (groupId.equals(dependency.getGroupId()) && artifactId.equals(dependency.getArtifactId())) return true;
        return false;
    }

    private String coordinate(String groupId, String artifactId) { return String.valueOf(groupId) + ":" + String.valueOf(artifactId); }
    private Plugin plugin(MavenProject project, String key) { return project.getPlugin(key); }

    enum ExecutorKind { SUREFIRE, FAILSAFE }

    record ExecutorPlan(
            String executionId,
            List<String> includeClassNameRegexes,
            List<String> excludeClassNameRegexes,
            List<String> executorJvmArgs,
            Map<String, String> executorSystemProperties,
            boolean testFailureIgnore) {
        ExecutorPlan {
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            executorJvmArgs = List.copyOf(executorJvmArgs == null ? List.of() : executorJvmArgs);
            executorSystemProperties = Map.copyOf(executorSystemProperties == null ? Map.of() : executorSystemProperties);
        }
    }

    record CompatibilityDecision(
            boolean compatible,
            Set<String> frameworks,
            String reason,
            ExecutorKind executorKind,
            String takeoverPhase,
            boolean deferFailureUntilVerify,
            List<ExecutorPlan> executorPlans) {
        CompatibilityDecision {
            frameworks = Set.copyOf(frameworks == null ? Set.of() : frameworks);
            executorPlans = List.copyOf(executorPlans == null ? List.of() : executorPlans);
        }

        static CompatibilityDecision takeOver(Set<String> frameworks, ExecutorKind executorKind, String phase,
                                              boolean deferFailure, List<ExecutorPlan> plans) {
            return new CompatibilityDecision(true, frameworks,
                    "Maven executor semantics are compatible; executable framework ownership will be proven by runtime preflight",
                    executorKind, phase, deferFailure, plans);
        }

        static CompatibilityDecision passThrough(String reason) {
            return new CompatibilityDecision(false, Set.of(), reason, null, null, false, List.of());
        }

        ExecutorPlan primaryPlan() {
            if (executorPlans.isEmpty()) throw new IllegalStateException("Compatible ScenarioMesh decision has no executor plans");
            return executorPlans.get(0);
        }

        List<String> includeClassNameRegexes() { return primaryPlan().includeClassNameRegexes(); }
        List<String> excludeClassNameRegexes() { return primaryPlan().excludeClassNameRegexes(); }
        List<String> executorJvmArgs() { return primaryPlan().executorJvmArgs(); }
        Map<String, String> executorSystemProperties() { return primaryPlan().executorSystemProperties(); }
        boolean testFailureIgnore() { return primaryPlan().testFailureIgnore(); }
    }

    private record SelectionOverride(boolean supported, List<String> includes, List<String> excludes, String reason) {
        private static SelectionOverride supported(List<String> includes, List<String> excludes) {
            return new SelectionOverride(true,
                    includes == null ? null : List.copyOf(includes),
                    excludes == null ? null : List.copyOf(excludes), null);
        }
        private static SelectionOverride unsupported(String reason) {
            return new SelectionOverride(false, null, null, reason);
        }
    }

    private record FrameworkSignals(boolean junitPlatform, boolean cucumberJUnit4, boolean testNg, boolean directJUnit4) {
        Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            if (junitPlatform) names.add("junit-platform");
            if (cucumberJUnit4) names.add("cucumber-junit4");
            if (testNg) names.add("testng");
            return Set.copyOf(names);
        }
    }
}
