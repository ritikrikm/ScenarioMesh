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
    void junit5PlusDirectJUnit4PassesThrough() {
        MavenProject project = project(
                dependency("org.junit.jupiter", "junit-jupiter"),
                dependency("junit", "junit"));

        var decision = detector.evaluate(session("test"), project);

        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("generic JUnit 4"), decision.reason());
    }

    @Test
    void multipleFrameworkSignalsAreCandidatesAndRuntimeDiscoveryProvesOwnership() {
        MavenProject project = project(
                dependency("org.junit.jupiter", "junit-jupiter"),
                dependency("org.testng", "testng"));

        var decision = detector.evaluate(session("test"), project);

        assertTrue(decision.compatible(), decision.reason());
        assertEquals(Set.of("junit-platform", "testng"), decision.frameworks());
        assertTrue(decision.reason().contains("runtime discovery"), decision.reason());
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
        for (Dependency dependency : dependencies) {
            model.addDependency(dependency);
        }
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
