package io.scenariomesh.maven.extension;

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
            "test", "surefire.includes", "surefire.excludes", "suiteXmlFiles", "dependenciesToScan");
    private static final Set<String> FAILSAFE_UNSAFE_SELECTION_PROPERTIES = Set.of(
            "it.test", "failsafe.includes", "failsafe.excludes", "suiteXmlFiles", "dependenciesToScan");
    private static final Set<String> CUCUMBER_SELECTION_PROPERTIES = Set.of(
            "cucumber.filter.tags", "cucumber.filter.name", "cucumber.features");
    private static final List<String> FRAMEWORK_PROPERTY_PREFIXES = List.of(
            "cucumber.", "junit.", "testng.");

    private final SurefireCompatibility surefireCompatibility = new SurefireCompatibility();
    private final FailsafeCompatibility failsafeCompatibility = new FailsafeCompatibility();

    CompatibilityDecision evaluate(MavenSession session, MavenProject project) {
        if (!requestsTestLifecycle(session)) {
            return CompatibilityDecision.passThrough("requested Maven goals do not reach the test lifecycle");
        }
        if (projectSkipsTests(project)) {
            return CompatibilityDecision.passThrough("project configuration explicitly skips tests");
        }

        FrameworkSignals frameworks = detectFrameworks(project);
        if (!frameworks.supported()) {
            return CompatibilityDecision.passThrough("no supported ScenarioMesh test framework was detected in the project model");
        }
        if (frameworks.directJUnit4() && !frameworks.cucumberJUnit4()) {
            return CompatibilityDecision.passThrough(
                    "generic JUnit 4 is present, but the MVP only supports JUnit 4 through the Cucumber JUnit 4 adapter");
        }
        // Dependency/model signals are candidates, not proof of executable ownership.
        // Multiple candidate frameworks are intentionally allowed through this gate so the
        // isolated runtime discovery process can probe every registered adapter. Runtime
        // discovery will take over only when ownership is unique (or explicitly configured);
        // ambiguous executable ownership fails safely instead of guessing or merging.
        if (propertyPresent(session, project, "groups") || propertyPresent(session, project, "excludedGroups")) {
            return CompatibilityDecision.passThrough(
                    "Maven group filtering is present; ScenarioMesh will not take over until discovery can reproduce group inclusion/exclusion exactly");
        }

        if (frameworks.cucumberJUnit4() || frameworks.junitPlatform()) {
            String projectOnlySelection = firstProjectOnlyProperty(session, project, CUCUMBER_SELECTION_PROPERTIES);
            if (projectOnlySelection != null) {
                return CompatibilityDecision.passThrough(
                        "Cucumber selection property '" + projectOnlySelection + "' is defined only as a Maven project property; "
                                + "ScenarioMesh cannot prove that native Surefire/Failsafe would expose it to the test JVM. "
                                + "Pass it with -D or through the executor's systemPropertyVariables to enable safe takeover.");
            }
        }

        Map<String, String> frameworkSystemProperties = invocationFrameworkSystemProperties(session);

        Optional<MavenExecutionPlan> executionPlan = MavenExecutionPlan.from(session);
        if (executionPlan.isEmpty()) {
            return CompatibilityDecision.passThrough("requested Maven lifecycle could not be determined safely");
        }

        Plugin failsafe = plugin(project, FAILSAFE);
        MavenExecutionPlan.PluginParticipation failsafeParticipation =
                executionPlan.get().failsafeParticipation(failsafe);
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
                    propertyName -> resolveProperty(session, project, propertyName));
            if (!analysis.supported()) {
                return CompatibilityDecision.passThrough(
                        "maven-failsafe-plugin participates in this invocation but ScenarioMesh cannot reproduce it safely: "
                                + analysis.reason());
            }
            if (!analysis.explicitlySkipped()) {
                String unsafe = firstPresentProperty(session, project, FAILSAFE_UNSAFE_SELECTION_PROPERTIES);
                if (unsafe != null) {
                    return CompatibilityDecision.passThrough(
                            "Failsafe test-selection property '" + unsafe
                                    + "' is present and is not yet reproduced by ScenarioMesh discovery");
                }
                Map<String, String> systemProperties = new LinkedHashMap<>(analysis.systemProperties());
                systemProperties.putAll(frameworkSystemProperties);
                return CompatibilityDecision.takeOver(
                        frameworks.names(),
                        ExecutorKind.FAILSAFE,
                        "integration-test",
                        true,
                        analysis.includeClassNameRegexes(),
                        analysis.excludeClassNameRegexes(),
                        analysis.jvmArgs(),
                        systemProperties,
                        analysis.testFailureIgnore());
            }
        }

        List<String> reasons = new ArrayList<>();
        Plugin surefire = plugin(project, SUREFIRE);
        SurefireCompatibility.Analysis surefireAnalysis = null;
        if (surefire != null) {
            surefireAnalysis = surefireCompatibility.analyze(surefire);
            if (surefireAnalysis.explicitlySkipsTests()) {
                return CompatibilityDecision.passThrough("maven-surefire-plugin explicitly skips tests");
            }
            reasons.addAll(surefireAnalysis.reasons());
        }

        String unsafe = firstPresentProperty(session, project, SUREFIRE_UNSAFE_SELECTION_PROPERTIES);
        if (unsafe != null) {
            reasons.add("Maven test-selection property '" + unsafe
                    + "' is present and is not yet reproduced by ScenarioMesh discovery");
        }
        if (!reasons.isEmpty()) {
            return CompatibilityDecision.passThrough(String.join("; ", reasons));
        }

        List<String> includes = surefireAnalysis == null
                ? SurefireCompatibility.defaultIncludeClassNameRegexes()
                : surefireAnalysis.includeClassNameRegexes();
        return CompatibilityDecision.takeOver(
                frameworks.names(), ExecutorKind.SUREFIRE, "test", false,
                includes, List.of(), List.of(), frameworkSystemProperties, false);
    }

    private Map<String, String> invocationFrameworkSystemProperties(MavenSession session) {
        Map<String, String> values = new LinkedHashMap<>();
        copyFrameworkProperties(session.getSystemProperties(), values);
        copyFrameworkProperties(session.getUserProperties(), values);
        return Map.copyOf(values);
    }

    private void copyFrameworkProperties(java.util.Properties source, Map<String, String> target) {
        if (source == null) return;
        source.forEach((rawKey, rawValue) -> {
            String key = String.valueOf(rawKey);
            if (FRAMEWORK_PROPERTY_PREFIXES.stream().anyMatch(key::startsWith)) {
                target.put(key, String.valueOf(rawValue));
            }
        });
    }

    private String firstProjectOnlyProperty(MavenSession session, MavenProject project, Set<String> keys) {
        for (String key : keys) {
            String projectValue = project.getProperties().getProperty(key);
            if (projectValue == null || projectValue.isBlank()) continue;
            String userValue = session.getUserProperties().getProperty(key);
            String systemValue = session.getSystemProperties().getProperty(key);
            if ((userValue == null || userValue.isBlank()) && (systemValue == null || systemValue.isBlank())) {
                return key;
            }
        }
        return null;
    }

    private String resolveProperty(MavenSession session, MavenProject project, String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value != null) return value;
        value = session.getSystemProperties().getProperty(key);
        if (value != null) return value;
        value = project.getProperties().getProperty(key);
        if (value != null) return value;

        return switch (key) {
            case "project.basedir", "basedir" -> project.getBasedir() == null ? null : project.getBasedir().getAbsolutePath();
            case "project.build.directory" -> project.getBuild() == null ? null : project.getBuild().getDirectory();
            case "project.build.outputDirectory" -> project.getBuild() == null ? null : project.getBuild().getOutputDirectory();
            case "project.build.testOutputDirectory" -> project.getBuild() == null ? null : project.getBuild().getTestOutputDirectory();
            case "project.artifactId" -> project.getArtifactId();
            case "project.groupId" -> project.getGroupId();
            case "project.version" -> project.getVersion();
            default -> null;
        };
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

    private boolean projectSkipsTests(MavenProject project) {
        return booleanProperty(project, "skipTests") || booleanProperty(project, "maven.test.skip");
    }

    private boolean booleanProperty(MavenProject project, String key) {
        String value = project.getProperties().getProperty(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private String firstPresentProperty(MavenSession session, MavenProject project, Set<String> keys) {
        for (String key : keys) if (propertyPresent(session, project, key)) return key;
        return null;
    }

    private boolean propertyPresent(MavenSession session, MavenProject project, String key) {
        String userValue = session.getUserProperties().getProperty(key);
        if (userValue != null && !userValue.isBlank()) return true;
        String systemValue = session.getSystemProperties().getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) return true;
        String projectValue = project.getProperties().getProperty(key);
        return projectValue != null && !projectValue.isBlank();
    }

    private FrameworkSignals detectFrameworks(MavenProject project) {
        Set<String> coordinates = new LinkedHashSet<>();
        if (project.getDependencies() != null) {
            for (Dependency dependency : project.getDependencies()) {
                coordinates.add(coordinate(dependency.getGroupId(), dependency.getArtifactId()));
            }
        }
        if (project.getArtifacts() != null) {
            for (Artifact artifact : project.getArtifacts()) {
                coordinates.add(coordinate(artifact.getGroupId(), artifact.getArtifactId()));
            }
        }
        boolean cucumberPlatform = coordinates.contains("io.cucumber:cucumber-junit-platform-engine");
        boolean cucumberJUnit4 = coordinates.contains("io.cucumber:cucumber-junit")
                || coordinates.contains("info.cukes:cucumber-junit");
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
        for (Dependency dependency : project.getDependencies()) {
            if (groupId.equals(dependency.getGroupId()) && artifactId.equals(dependency.getArtifactId())) return true;
        }
        return false;
    }

    private String coordinate(String groupId, String artifactId) {
        return String.valueOf(groupId) + ":" + String.valueOf(artifactId);
    }

    private Plugin plugin(MavenProject project, String key) {
        return project.getPlugin(key);
    }

    enum ExecutorKind { SUREFIRE, FAILSAFE }

    record CompatibilityDecision(
            boolean compatible,
            Set<String> frameworks,
            String reason,
            ExecutorKind executorKind,
            String takeoverPhase,
            boolean deferFailureUntilVerify,
            List<String> includeClassNameRegexes,
            List<String> excludeClassNameRegexes,
            List<String> executorJvmArgs,
            Map<String, String> executorSystemProperties,
            boolean testFailureIgnore) {
        CompatibilityDecision {
            frameworks = Set.copyOf(frameworks == null ? Set.of() : frameworks);
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            executorJvmArgs = List.copyOf(executorJvmArgs == null ? List.of() : executorJvmArgs);
            executorSystemProperties = Map.copyOf(executorSystemProperties == null ? Map.of() : executorSystemProperties);
        }

        static CompatibilityDecision takeOver(
                Set<String> frameworks,
                ExecutorKind executorKind,
                String phase,
                boolean deferFailure,
                List<String> includes,
                List<String> excludes,
                List<String> jvmArgs,
                Map<String, String> systemProperties,
                boolean testFailureIgnore) {
            return new CompatibilityDecision(
                    true,
                    frameworks,
                    "supported framework/model configuration detected; executable adapter ownership will be proven by runtime discovery",
                    executorKind,
                    phase,
                    deferFailure,
                    includes,
                    excludes,
                    jvmArgs,
                    systemProperties,
                    testFailureIgnore);
        }

        static CompatibilityDecision passThrough(String reason) {
            return new CompatibilityDecision(false, Set.of(), reason, null, null, false,
                    List.of(), List.of(), List.of(), Map.of(), false);
        }
    }

    private record FrameworkSignals(
            boolean junitPlatform,
            boolean cucumberJUnit4,
            boolean testNg,
            boolean directJUnit4) {
        boolean supported() {
            return junitPlatform || cucumberJUnit4 || testNg;
        }

        Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            if (junitPlatform) names.add("junit-platform");
            if (cucumberJUnit4) names.add("cucumber-junit4");
            if (testNg) names.add("testng");
            return Set.copyOf(names);
        }
    }
}
