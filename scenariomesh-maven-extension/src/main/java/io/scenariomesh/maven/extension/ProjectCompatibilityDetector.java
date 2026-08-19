package io.scenariomesh.maven.extension;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            FailsafeCompatibility.Analysis analysis = failsafeCompatibility.analyze(failsafe, failsafeParticipation);
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
                return CompatibilityDecision.takeOver(
                        frameworks.names(), ExecutorKind.FAILSAFE, "integration-test", true,
                        analysis.includeClassNameRegexes(), analysis.excludeClassNameRegexes());
            }
        }

        List<String> reasons = new ArrayList<>();
        Plugin surefire = plugin(project, SUREFIRE);
        if (surefire != null) {
            SurefireCompatibility.Analysis analysis = surefireCompatibility.analyze(surefire);
            if (analysis.explicitlySkipsTests()) {
                return CompatibilityDecision.passThrough("maven-surefire-plugin explicitly skips tests");
            }
            reasons.addAll(analysis.reasons());
        }

        String unsafe = firstPresentProperty(session, project, SUREFIRE_UNSAFE_SELECTION_PROPERTIES);
        if (unsafe != null) {
            reasons.add("Maven test-selection property '" + unsafe
                    + "' is present and is not yet reproduced by ScenarioMesh discovery");
        }
        if ((propertyPresent(session, project, "groups") || propertyPresent(session, project, "excludedGroups"))
                && !frameworks.testNgOnly()) {
            reasons.add("group filtering is present for a non-TestNG-only project and cannot yet be guaranteed equivalent");
        }
        if (!reasons.isEmpty()) {
            return CompatibilityDecision.passThrough(String.join("; ", reasons));
        }
        return CompatibilityDecision.takeOver(
                frameworks.names(), ExecutorKind.SUREFIRE, "test", false, List.of(), List.of());
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

    private Plugin plugin(MavenProject project, String key) { return project.getPlugin(key); }

    enum ExecutorKind { SUREFIRE, FAILSAFE }

    record CompatibilityDecision(boolean compatible,
                                 Set<String> frameworks,
                                 String reason,
                                 ExecutorKind executorKind,
                                 String takeoverPhase,
                                 boolean deferFailureUntilVerify,
                                 List<String> includeClassNameRegexes,
                                 List<String> excludeClassNameRegexes) {
        CompatibilityDecision {
            frameworks = Set.copyOf(frameworks == null ? Set.of() : frameworks);
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
        }
        static CompatibilityDecision takeOver(Set<String> frameworks,
                                              ExecutorKind executorKind,
                                              String phase,
                                              boolean deferFailure,
                                              List<String> includes,
                                              List<String> excludes) {
            return new CompatibilityDecision(true, frameworks, "supported framework/model configuration detected",
                    executorKind, phase, deferFailure, includes, excludes);
        }
        static CompatibilityDecision passThrough(String reason) {
            return new CompatibilityDecision(false, Set.of(), reason, null, null, false, List.of(), List.of());
        }
    }

    private record FrameworkSignals(boolean junitPlatform, boolean cucumberJUnit4, boolean testNg, boolean directJUnit4) {
        boolean supported(){return junitPlatform||cucumberJUnit4||testNg;}
        boolean testNgOnly(){return testNg&&!junitPlatform&&!cucumberJUnit4;}
        Set<String> names(){
            Set<String> names=new LinkedHashSet<>();
            if(junitPlatform)names.add("junit-platform");
            if(cucumberJUnit4)names.add("cucumber-junit4");
            if(testNg)names.add("testng");
            return Set.copyOf(names);
        }
    }
}
