package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExecutionPlanTest {

    @Test
    void cleanVerifyRemainsAReproducibleLifecycleInvocation() {
        MavenExecutionPlan plan = MavenExecutionPlan.fromGoals(List.of("clean", "verify")).orElseThrow();
        assertEquals("verify", plan.terminalPhase());
    }

    @Test
    void directPluginGoalIsNotCollapsedIntoALifecyclePhase() {
        assertTrue(MavenExecutionPlan.fromGoals(List.of("failsafe:integration-test")).isEmpty());
        assertTrue(MavenExecutionPlan.fromGoals(List.of(
                "org.apache.maven.plugins:maven-failsafe-plugin:3.5.0:integration-test")).isEmpty());
    }

    @Test
    void mixedDirectGoalAndVerifyIsConservative() {
        assertTrue(MavenExecutionPlan.fromGoals(List.of("clean", "dependency:build-classpath", "verify")).isEmpty());
    }

    @Test
    void unknownCustomPhaseIsConservative() {
        assertTrue(MavenExecutionPlan.fromGoals(List.of("company-test-phase")).isEmpty());
    }

    @Test
    void failsafeBoundToIntegrationTestDoesNotBlockTestPhase() {
        MavenExecutionPlan plan = MavenExecutionPlan.through("test");
        MavenExecutionPlan.PluginParticipation participation = plan.failsafeParticipation(
                failsafeExecution("it", null, "integration-test", "verify"));

        assertEquals(MavenExecutionPlan.ParticipationState.INACTIVE, participation.state());
    }

    @Test
    void failsafeBoundToIntegrationTestIsActiveForVerify() {
        MavenExecutionPlan plan = MavenExecutionPlan.through("verify");
        MavenExecutionPlan.PluginParticipation participation = plan.failsafeParticipation(
                failsafeExecution("it", null, "integration-test", "verify"));

        assertEquals(MavenExecutionPlan.ParticipationState.ACTIVE, participation.state());
        assertTrue(participation.evidence().stream().anyMatch(value -> value.contains("integration-test")));
    }

    @Test
    void customFailsafeExecutionBoundToTestBlocksTestPhase() {
        MavenExecutionPlan plan = MavenExecutionPlan.through("test");
        MavenExecutionPlan.PluginParticipation participation = plan.failsafeParticipation(
                failsafeExecution("custom", "test", "integration-test"));

        assertEquals(MavenExecutionPlan.ParticipationState.ACTIVE, participation.state());
        assertTrue(participation.evidence().contains("custom@test"));
    }

    @Test
    void unknownFailsafeGoalWithoutPhaseIsConservative() {
        MavenExecutionPlan plan = MavenExecutionPlan.through("test");
        MavenExecutionPlan.PluginParticipation participation = plan.failsafeParticipation(
                failsafeExecution("custom", null, "future-goal"));

        assertEquals(MavenExecutionPlan.ParticipationState.UNKNOWN, participation.state());
    }

    private Plugin failsafeExecution(String id, String phase, String... goals) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-failsafe-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase(phase);
        execution.setGoals(List.of(goals));
        plugin.addExecution(execution);
        return plugin;
    }
}
