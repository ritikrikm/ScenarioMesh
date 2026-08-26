package io.scenariomesh.maven.extension;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownstreamLifecycleCompatibilityTest {
    private final DownstreamLifecycleCompatibility compatibility = new DownstreamLifecycleCompatibility();

    @Test
    void unknownIntegrationTestPeerFailsClosedBecauseSamePhaseOrderMayMatter() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("com.example", "integration-side-effect", "integration-test"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.FAILSAFE);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("integration-test"), analysis.reason());
        assertTrue(analysis.reason().contains("same-phase ordering"), analysis.reason());
    }

    @Test
    void preIntegrationSetupRemainsAllowedBecauseMavenStillExecutesItNatively() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("com.example", "environment-setup", "pre-integration-test"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.FAILSAFE);

        assertTrue(analysis.supported(), analysis.reason());
    }

    @Test
    void unknownPostIntegrationConsumerFailsClosedForFailsafeTakeover() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("com.example", "artifact-consumer", "post-integration-test"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.FAILSAFE);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("com.example:artifact-consumer"), analysis.reason());
    }

    @Test
    void unknownVerifyConsumerFailsClosedForFailsafeTakeover() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("com.example", "verification-uploader", "verify"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.FAILSAFE);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("verify"), analysis.reason());
    }

    @Test
    void knownFailsafeAndCluecumberContractsRemainAllowed() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("org.apache.maven.plugins", "maven-failsafe-plugin", "verify"));
        project.getBuild().addPlugin(plugin("com.trivago.rta", "cluecumber-report-plugin", "verify"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.FAILSAFE);

        assertTrue(analysis.supported(), analysis.reason());
    }

    @Test
    void surefireTakeoverDoesNotClaimOwnershipOfIntegrationLifecycleConsumers() {
        MavenProject project = project();
        project.getBuild().addPlugin(plugin("com.example", "verification-uploader", "verify"));

        var analysis = compatibility.analyze(project, ProjectCompatibilityDetector.ExecutorKind.SUREFIRE);

        assertTrue(analysis.supported(), analysis.reason());
    }

    private MavenProject project() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");
        model.setBuild(new Build());
        return new MavenProject(model);
    }

    private Plugin plugin(String groupId, String artifactId, String phase) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        PluginExecution execution = new PluginExecution();
        execution.setId("fixture-execution");
        execution.setPhase(phase);
        execution.setGoals(List.of("run"));
        plugin.addExecution(execution);
        return plugin;
    }
}
