package io.scenariomesh.maven.extension;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCompatibilityDetectorTest {
    private final ProjectCompatibilityDetector detector = new ProjectCompatibilityDetector();

    @Test
    void singleJUnitPlatformOwnerCanTakeOver() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(ProjectCompatibilityDetector.ExecutorKind.SUREFIRE, decision.executorKind());
        assertFalse(decision.includeClassNameRegexes().isEmpty());
    }

    @Test
    void surefireIncludeAndExcludeUserPropertiesAreTranslatedThroughTheProvenPatternParser() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "**/*SmokeTest.java,**/*ApiTest.java");
        session.getUserProperties().setProperty("surefire.excludes", "**/*SlowTest.java");

        var decision = detector.evaluate(session, project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(2, decision.includeClassNameRegexes().size());
        assertEquals(1, decision.excludeClassNameRegexes().size());
        assertNotEquals(SurefireCompatibility.defaultIncludeClassNameRegexes(), decision.includeClassNameRegexes());
    }

    @Test
    void unsupportedSurefireIncludePropertyFailsClosed() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "!Unstable*");

        var decision = detector.evaluate(session, project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("proven Maven selector subset"), decision.reason());
    }

    @Test
    void methodLevelTestSelectorStillPassesThroughUntilProviderSemanticsAreReproduced() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("test", "LoginTest#happyPath");

        var decision = detector.evaluate(session, project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("test-selection property 'test'"), decision.reason());
    }

    @Test
    void junit5PlusDirectJUnit4PassesThrough() {
        MavenProject project = project(
                dependency("org.junit.jupiter", "junit-jupiter"),
                dependency("junit", "junit"));

        var decision = detector.evaluate(session("test"), project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("generic JUnit 4"), decision.reason());
    }

    @Test
    void multipleFrameworkSignalsAreCandidatesAndRuntimePreflightProvesOwnership() {
        MavenProject project = project(
                dependency("org.junit.jupiter", "junit-jupiter"),
                dependency("org.testng", "testng"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(Set.of("junit-platform", "testng"), decision.frameworks());
        assertTrue(decision.reason().contains("runtime preflight"), decision.reason());
    }

    @Test
    void unknownFrameworkDependencyCanReachRuntimePreflightWithoutBeingAssumedOwnable() {
        MavenProject project = project(dependency("com.example", "future-test-engine"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertTrue(decision.frameworks().isEmpty());
        assertTrue(decision.reason().contains("runtime preflight"), decision.reason());
    }

    @Test
    void cucumberJUnit4PlusTestNgDoesNotFailCompatibilityOnDependenciesAlone() {
        MavenProject project = project(
                dependency("io.cucumber", "cucumber-junit"),
                dependency("org.testng", "testng"),
                dependency("junit", "junit"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(Set.of("cucumber-junit4", "testng"), decision.frameworks());
    }

    @Test
    void cucumberJUnit4DependencyCanStillTakeOverDespiteItsJUnit4Dependency() {
        MavenProject project = project(
                dependency("io.cucumber", "cucumber-junit"),
                dependency("junit", "junit"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(List.of("cucumber-junit4"), decision.frameworks().stream().sorted().toList());
    }

    @Test
    void testNgGroupFilteringPassesThroughUntilDiscoveryCanReproduceItExactly() {
        MavenProject project = project(dependency("org.testng", "testng"));
        project.getProperties().setProperty("groups", "smoke,api");
        project.getProperties().setProperty("excludedGroups", "slow");

        var decision = detector.evaluate(session("test"), project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("group filtering"), decision.reason());
    }

    @Test
    void cucumberCliTagFilterIsForwardedIntoDiscoveryAndWorkers() {
        MavenProject project = project(
                dependency("io.cucumber", "cucumber-junit"),
                dependency("junit", "junit"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("cucumber.filter.tags", "@smoke and not @slow");

        var decision = detector.evaluate(session, project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals("@smoke and not @slow", decision.executorSystemProperties().get("cucumber.filter.tags"));
    }

    @Test
    void cucumberMavenJvmPropertyIsForwardedIncludingCustomPluginConfiguration() {
        MavenProject project = project(
                dependency("io.cucumber", "cucumber-junit"),
                dependency("junit", "junit"));
        MavenSession session = session("test");
        session.getSystemProperties().setProperty("cucumber.plugin", "com.example.CustomReporter,pretty");
        session.getSystemProperties().setProperty("cucumber.filter.name", "Checkout.*");

        var decision = detector.evaluate(session, project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals("com.example.CustomReporter,pretty", decision.executorSystemProperties().get("cucumber.plugin"));
        assertEquals("Checkout.*", decision.executorSystemProperties().get("cucumber.filter.name"));
    }

    @Test
    void projectOnlyCucumberSelectionFailsClosedBecauseNativeForkExposureIsAmbiguous() {
        MavenProject project = project(
                dependency("io.cucumber", "cucumber-junit"),
                dependency("junit", "junit"));
        project.getProperties().setProperty("cucumber.filter.tags", "@smoke");

        var decision = detector.evaluate(session("test"), project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("defined only as a Maven project property"), decision.reason());
    }

    private MavenSession session(String... goals) {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setGoals(List.of(goals));
        return new MavenSession(null, null, request, new DefaultMavenExecutionResult());
    }

    private MavenProject project(Dependency... dependencies) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");
        model.setBuild(new Build());
        for (Dependency dependency : dependencies) model.addDependency(dependency);
        return new MavenProject(model);
    }

    private Dependency dependency(String groupId, String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        dependency.setScope("test");
        return dependency;
    }
}
