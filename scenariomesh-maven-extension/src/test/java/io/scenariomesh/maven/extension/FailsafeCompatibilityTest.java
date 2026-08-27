package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeCompatibilityTest {

    private final FailsafeCompatibility compatibility = new FailsafeCompatibility();

    @Test
    void defaultSelectionExcludesInnerClassesLikeNativeFailsafe() {
        Plugin plugin = pluginWithExecution();
        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertTrue(analysis.supported(), analysis.reason());
        var plan = analysis.executionPlans().get(0);
        assertTrue(plan.excludeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/LoginIT$Nested.class")));
        assertTrue(plan.excludeClassNameRegexes().stream()
                .noneMatch(regex -> Pattern.matches(regex, "example/LoginIT.class")));
    }

    @Test
    void translatesJvmPropertiesAndFailureIgnoreWhenRetriesAreZero() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");
        add(pluginConfig, "argLine", "-Xmx512m -Dfile.encoding=UTF-8");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "language", "${language}");
        add(properties, "environment", "qa");
        pluginConfig.addChild(properties);
        add(pluginConfig, "testFailureIgnore", "true");
        add(pluginConfig, "rerunFailingTestsCount", "${retry.count}");
        plugin.setConfiguration(pluginConfig);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        Map<String,String> values = Map.of("language", "EN", "retry.count", "0");
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, values::get);
        FailsafeCompatibility.ExecutionPlan plan = analysis.executionPlans().get(0);

        assertTrue(analysis.supported());
        assertEquals(List.of("-Xmx512m", "-Dfile.encoding=UTF-8"), plan.jvmArgs());
        assertEquals(Map.of("language", "EN", "environment", "qa"), plan.systemProperties());
        assertTrue(plan.testFailureIgnore());
    }

    @Test
    void positiveRerunCountRemainsPassThroughMaterial() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "2");
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("will not risk duplicating retries"));
    }

    @Test
    void executionLevelScalarSettingsOverridePluginLevelSettings() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");
        add(pluginConfig, "testFailureIgnore", "false");
        add(pluginConfig, "argLine", "-Xmx256m");
        plugin.setConfiguration(pluginConfig);

        PluginExecution execution = plugin.getExecutions().get(0);
        Xpp3Dom executionConfig = new Xpp3Dom("configuration");
        add(executionConfig, "testFailureIgnore", "true");
        add(executionConfig, "argLine", "-Xmx1g");
        execution.setConfiguration(executionConfig);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);
        FailsafeCompatibility.ExecutionPlan plan = analysis.executionPlans().get(0);

        assertTrue(analysis.supported());
        assertTrue(plan.testFailureIgnore());
        assertEquals(List.of("-Xmx1g"), plan.jvmArgs());
    }

    @Test
    void unresolvedPropertyFailsSafe() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "environment", "${missing.environment}");
        config.addChild(properties);
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("unresolved Maven property"));
    }

    @Test
    void stableLateArgLinePropertyIsResolvedWithoutVendorSpecificLogic() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "argLine", "@{instrumentation.args} -Dwork.dir=${work.dir}");
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(
                plugin,
                participation,
                key -> "work.dir".equals(key) ? "/tmp/work space" : null,
                key -> "instrumentation.args".equals(key) ? "-javaagent:/tmp/agent.jar" : null);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of("-javaagent:/tmp/agent.jar", "-Dwork.dir=/tmp/work", "space"),
                analysis.executionPlans().get(0).jvmArgs());
    }

    @Test
    void mutableOrUnknownLateArgLinePropertyFailsClosed() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "argLine", "@{generated.agent.args} -Xmx512m");
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(
                plugin, participation, key -> null, key -> null);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("earlier lifecycle plugin may mutate it"), analysis.reason());
    }

    @Test
    void arbitraryDynamicSystemPropertiesArePreservedGenerically() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "remote.grid.config", "${grid.config.path}");
        add(properties, "remote.grid.user", "${grid.user}");
        add(properties, "custom.feature.flag", "enabled");
        config.addChild(properties);
        plugin.setConfiguration(config);

        Map<String, String> values = Map.of(
                "grid.config.path", "/tmp/grid.yml",
                "grid.user", "ci-user");
        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, values::get);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(Map.of(
                "remote.grid.config", "/tmp/grid.yml",
                "remote.grid.user", "ci-user",
                "custom.feature.flag", "enabled"),
                analysis.executionPlans().get(0).systemProperties());
    }

    @Test
    void multipleFailsafeExecutionsRemainIndependentPlans() {
        Plugin plugin = pluginWithExecution();
        PluginExecution second = execution("regression-tests");
        Xpp3Dom firstConfig = new Xpp3Dom("configuration");
        firstConfig.addChild(patterns("includes", "**/SmokeIT.java"));
        plugin.getExecutions().get(0).setConfiguration(firstConfig);
        Xpp3Dom secondConfig = new Xpp3Dom("configuration");
        secondConfig.addChild(patterns("includes", "**/RegressionIT.java"));
        Xpp3Dom props = new Xpp3Dom("systemPropertyVariables");
        add(props, "environment", "staging");
        secondConfig.addChild(props);
        second.setConfiguration(secondConfig);
        plugin.addExecution(second);

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(2, analysis.executionPlans().size());
        assertEquals("integration-tests", analysis.executionPlans().get(0).executionId());
        assertEquals("regression-tests", analysis.executionPlans().get(1).executionId());
        assertFalse(analysis.executionPlans().get(0).includeClassNameRegexes()
                .equals(analysis.executionPlans().get(1).includeClassNameRegexes()));
        assertEquals("staging", analysis.executionPlans().get(1).systemProperties().get("environment"));
    }

    @Test
    void overlappingNativeExecutionsAreNotDeduplicated() {
        Plugin plugin = pluginWithExecution();
        plugin.addExecution(execution("same-tests-again"));

        MavenExecutionPlan.PluginParticipation participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(2, analysis.executionPlans().size(),
                "Two native Maven executions must remain two ScenarioMesh execution plans even if their selectors overlap");
    }

    private Plugin pluginWithExecution() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-failsafe-plugin");
        plugin.addExecution(execution("integration-tests"));
        return plugin;
    }

    private PluginExecution execution(String id) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase("integration-test");
        execution.setGoals(List.of("integration-test"));
        return execution;
    }

    private Xpp3Dom patterns(String name, String value) {
        Xpp3Dom parent = new Xpp3Dom(name);
        add(parent, name.equals("includes") ? "include" : "exclude", value);
        return parent;
    }

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
