package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeCompatibilityTest {

    private final FailsafeCompatibility compatibility = new FailsafeCompatibility();

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

        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        Map<String,String> values = Map.of("language", "EN", "retry.count", "0");

        FailsafeCompatibility.Analysis analysis =
                compatibility.analyze(plugin, participation, values::get);

        assertTrue(analysis.supported());
        assertEquals(List.of("-Xmx512m", "-Dfile.encoding=UTF-8"), analysis.jvmArgs());
        assertEquals(Map.of("language", "EN", "environment", "qa"), analysis.systemProperties());
        assertTrue(analysis.testFailureIgnore());
    }

    @Test
    void positiveRerunCountRemainsPassThroughMaterial() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "2");
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
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

        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertTrue(analysis.supported());
        assertTrue(analysis.testFailureIgnore());
        assertEquals(List.of("-Xmx1g"), analysis.jvmArgs());
    }

    @Test
    void unresolvedPropertyFailsSafe() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "environment", "${missing.environment}");
        config.addChild(properties);
        plugin.setConfiguration(config);

        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, key -> null);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("unresolved Maven property"));
    }

    private Plugin pluginWithExecution() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-failsafe-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId("integration-tests");
        execution.setPhase("integration-test");
        execution.setGoals(List.of("integration-test"));
        plugin.addExecution(execution);
        return plugin;
    }

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
