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
            "dependenciesToScan");
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
        if (frameworks.cucumberJUnit4() || frameworks.junitPlatform()) {
            String projectOnlySelection = firstProjectOnlyProperty(properties, CUCUMBER_SELECTION_PROPERTIES);
            if (projectOnlySelection != null) {
                return CompatibilityDecision.passThrough(
                        "Cucumber selection property '" + projectOnlySelection + "' is defined only as a Maven project property in the effective model; "
                                + "ScenarioMesh cannot prove that native Surefire/Failsafe would expose it to the test JVM. "
                                + "Pass it with -D or through the executor's systemPropertyVariables to enable safe takeover.");
            }
        }

        Map<String, String> frameworkSystemProperties = new LinkedHashMap<>(
                effectiveFrameworkSystemProperties(session, properties));
        if (frameworks.cucumberJUnit4()) {
            frameworkSystemProperties.put(RuntimePropertyNames.JUNIT_VINTAGE_DISABLED, "true");
        }
        frameworkSystemProperties = Map.copyOf(frameworkSystemProperties);

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
                    properties::resolveStableLate,
                    session.getUserProperties());
            if (!analysis.supported()) {
                return CompatibilityDecision.passThrough(
                        "maven-failsafe-plugin participates in this invocation but ScenarioMesh cannot reproduce it safely: "
                                + analysis.reason());
            }
            if (suiteXmlWithGroupSelection(properties, analysis.executionPlans())) {
                return CompatibilityDecision.passThrough(
                        "Failsafe suiteXmlFiles combined with groups/excludedGroups remains native until TestNG suite materialization and zero-selection semantics are proven equivalent");
            }
            if (groupSelectionRequested(properties, analysis.executionPlans()) && !frameworks.testNgOnly()) {
                return CompatibilityDecision.passThrough(
                        "Failsafe groups/excludedGroups are currently owned only for a pure TestNG provider set; "
                                + "mixed, JUnit Platform, and JUnit4 group semantics remain native");
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

                Map<String, String> finalFrameworkSystemProperties = frameworkSystemProperties;
                List<ExecutorPlan> plans = new ArrayList<>();
                for (FailsafeCompatibility.ExecutionPlan plan : analysis.executionPlans()) {
                    if (plan.explicitlySkipped()) continue;
                    SelectionOverride selectionOverride = commandSelection.present()
                            ? SelectionOverride.supported(commandSelection.includeRegexes(), List.of(), Map.of())
                            : selectionOverride(properties, "failsafe.includes", "failsafe.excludes", "Failsafe",
                                    plan.includedTestPatterns(), plan.excludedTestPatterns());
                    if (!selectionOverride.supported()) {
                        return CompatibilityDecision.passThrough("Failsafe execution '" + plan.executionId()
                                + "': " + selectionOverride.reason());
                    }
                    Map<String, String> planProperties = new LinkedHashMap<>(plan.systemProperties());
                    planProperties.putAll(finalFrameworkSystemProperties);
                    applySelectionProperties(planProperties, commandSelection, selectionOverride);
                    plans.add(new ExecutorPlan(
                            plan.executionId(),
                            selectionOverride.includes() == null
                                    ? plan.includeClassNameRegexes() : selectionOverride.includes(),
                            selectionOverride.excludes() == null
                                    ? plan.excludeClassNameRegexes() : selectionOverride.excludes(),
                            plan.jvmArgs(),
                            planProperties,
                            plan.testFailureIgnore()));
                }
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
            surefireAnalysis = surefireCompatibility.analyze(
                    surefire, properties::resolve, session.getUserProperties());
            if (surefireAnalysis.explicitlySkipsTests()) return CompatibilityDecision.passThrough("maven-surefire-plugin explicitly skips tests");
            reasons.addAll(surefireAnalysis.reasons());
            if (suiteXmlWithGroupSelection(properties, surefireAnalysis.systemProperties())) {
                reasons.add("Surefire suiteXmlFiles combined with groups/excludedGroups remains native until TestNG suite materialization and zero-selection semantics are proven equivalent");
            } else if (groupSelectionRequested(properties, surefireAnalysis.systemProperties()) && !frameworks.testNgOnly()) {
                reasons.add("Surefire groups/excludedGroups are currently owned only for a pure TestNG provider set; "
                        + "mixed, JUnit Platform, and JUnit4 group semantics remain native");
            }
        } else if (executorGroupPropertyPresent(properties) && !frameworks.testNgOnly()) {
            reasons.add("Surefire groups/excludedGroups are currently owned only for a pure TestNG provider set");
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
            selectionOverride = SelectionOverride.supported(commandSelection.includeRegexes(), List.of(), Map.of());
        } else {
            List<String> baseIncludes = surefireAnalysis == null
                    ? SurefireCompatibility.defaultIncludePatterns() : surefireAnalysis.includedTestPatterns();
            List<String> baseExcludes = surefireAnalysis == null
                    ? SurefireCompatibility.defaultExcludePatterns() : surefireAnalysis.excludedTestPatterns();
            selectionOverride = selectionOverride(
                    properties, "surefire.includes", "surefire.excludes", "Surefire", baseIncludes, baseExcludes);
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
        applySelectionProperties(surefireSystemProperties, commandSelection, selectionOverride);
        return CompatibilityDecision.takeOver(
                frameworks.names(), ExecutorKind.SUREFIRE, "test", false,
                List.of(new ExecutorPlan("default-test", includes, excludes, List.of(), surefireSystemProperties, false)));
    }

    private void applySelectionProperties(Map<String, String> target,
                                          CommandLineClassSelection.Analysis commandSelection,
                                          SelectionOverride selectionOverride) {
        if (commandSelection != null && commandSelection.present()) {
            target.remove(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS);
            target.remove(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS);
            target.remove(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION);
            if (commandSelection.testListExpression() != null) {
                target.put(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION, commandSelection.testListExpression());
            }
            return;
        }
        target.remove(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION);
        if (selectionOverride != null && selectionOverride.present()) {
            target.remove(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS);
            target.remove(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS);
            target.putAll(selectionOverride.internalProperties());
        }
    }

    private boolean suiteXmlWithGroupSelection(EffectivePropertyResolver properties,
                                               List<FailsafeCompatibility.ExecutionPlan> plans) {
        return plans.stream().anyMatch(plan -> !plan.explicitlySkipped()
                && suiteXmlWithGroupSelection(properties, plan.systemProperties()));
    }

    private boolean suiteXmlWithGroupSelection(EffectivePropertyResolver properties,
                                               Map<String, String> planProperties) {
        return planProperties.containsKey(SurefireCompatibility.TESTNG_SUITE_XML_FILES_PROPERTY)
                && groupSelectionRequested(properties, planProperties);
    }

    private boolean groupSelectionRequested(EffectivePropertyResolver properties,
                                            List<FailsafeCompatibility.ExecutionPlan> plans) {
        if (executorGroupPropertyPresent(properties)) return true;
        return plans.stream().anyMatch(plan -> groupSelectionRequested(plan.systemProperties()));
    }

    private boolean groupSelectionRequested(EffectivePropertyResolver properties, Map<String, String> planProperties) {
        return executorGroupPropertyPresent(properties) || groupSelectionRequested(planProperties);
    }

    private boolean executorGroupPropertyPresent(EffectivePropertyResolver properties) {
        return properties.present("groups") || properties.present("excludedGroups");
    }

    private boolean groupSelectionRequested(Map<String, String> properties) {
        return properties.containsKey("groups") || properties.containsKey("excludedGroups");
    }

    private SelectionOverride selectionOverride(EffectivePropertyResolver properties,
                                                String includesKey,
                                                String excludesKey,
                                                String executorName,
                                                List<String> baseIncludes,
                                                List<String> baseExcludes) {
        boolean includesPresent = properties.present(includesKey);
        boolean excludesPresent = properties.present(excludesKey);
        if (!includesPresent && !excludesPresent) return SelectionOverride.absent();

        String includesValue = includesPresent ? properties.resolve(includesKey) : null;
        String excludesValue = excludesPresent ? properties.resolve(excludesKey) : null;
        if (includesPresent && (includesValue == null || includesValue.isBlank())) {
            return SelectionOverride.unsupported(executorName + " selection property '" + includesKey
                    + "' is blank; ScenarioMesh will not guess Maven's collection binding semantics");
        }
        if (excludesPresent && (excludesValue == null || excludesValue.isBlank())) {
            return SelectionOverride.unsupported(executorName + " selection property '" + excludesKey
                    + "' is blank; ScenarioMesh will not guess Maven's collection binding semantics");
        }

        List<String> effectiveIncludes = includesPresent ? List.of(includesValue) : baseIncludes;
        List<String> effectiveExcludes = excludesPresent ? List.of(excludesValue) : baseExcludes;
        ConfiguredTestSelection.Plan plan = ConfiguredTestSelection.analyze(
                effectiveIncludes, effectiveExcludes, baseIncludes, baseExcludes);
        if (!plan.supported()) {
            return SelectionOverride.unsupported(executorName + " user-property selection cannot be reproduced: "
                    + plan.reason());
        }
        return SelectionOverride.supported(plan.includeClassNameRegexes(),
                plan.excludeClassNameRegexes(), plan.internalProperties());
    }

    private Map<String, String> effectiveFrameworkSystemProperties(
            MavenSession session, EffectivePropertyResolver effectiveProperties) {
        Map<String, String> values = new LinkedHashMap<>();
        copyFrameworkProperties(session.getSystemProperties(), values);
        copyFrameworkProperties(session.getUserProperties(), values);
        for (String key : EXECUTOR_FRAMEWORK_PROPERTIES) {
            String value = effectiveProperties.resolve(key);
            if (value != null && !value.isEmpty()) values.put(key, value);
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

    private record SelectionOverride(boolean present, boolean supported, List<String> includes, List<String> excludes,
                                     Map<String, String> internalProperties, String reason) {
        private SelectionOverride {
            internalProperties = Map.copyOf(internalProperties == null ? Map.of() : internalProperties);
        }
        private static SelectionOverride absent() {
            return new SelectionOverride(false, true, null, null, Map.of(), null);
        }
        private static SelectionOverride supported(List<String> includes, List<String> excludes,
                                                   Map<String, String> internalProperties) {
            return new SelectionOverride(true, true,
                    includes == null ? null : List.copyOf(includes),
                    excludes == null ? null : List.copyOf(excludes), internalProperties, null);
        }
        private static SelectionOverride unsupported(String reason) {
            return new SelectionOverride(true, false, null, null, Map.of(), reason);
        }
    }

    private record FrameworkSignals(boolean junitPlatform, boolean cucumberJUnit4, boolean testNg, boolean directJUnit4) {
        boolean testNgOnly() {
            return testNg && !junitPlatform && !cucumberJUnit4 && !directJUnit4;
        }

        Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            if (junitPlatform) names.add("junit-platform");
            if (cucumberJUnit4) names.add("cucumber-junit4");
            if (testNg) names.add("testng");
            if (directJUnit4 && !cucumberJUnit4) names.add("junit4-vintage");
            return Set.copyOf(names);
        }
    }
}
