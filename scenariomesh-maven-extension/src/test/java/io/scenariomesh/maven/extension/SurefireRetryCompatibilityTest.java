package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireRetryCompatibilityTest {
    private final SurefireCompatibility compatibility = new SurefireCompatibility();

    @Test
    void positiveRerunAndFlakeThresholdArePreserved() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "${retry.count}");
        add(config, "failOnFlakeCount", "${flake.threshold}");
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin,
                Map.of("retry.count", "2", "flake.threshold", "3")::get);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("2", analysis.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("3", analysis.systemProperties().get(RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT));
    }

    @Test
    void executionConfigurationOverridesPluginRetryPolicy() {
        Plugin plugin = plugin();
        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");
        add(pluginConfig, "rerunFailingTestsCount", "1");
        add(pluginConfig, "failOnFlakeCount", "1");
        plugin.setConfiguration(pluginConfig);

        Xpp3Dom executionConfig = new Xpp3Dom("configuration");
        add(executionConfig, "rerunFailingTestsCount", "5");
        add(executionConfig, "failOnFlakeCount", "2");
        plugin.getExecutions().get(0).setConfiguration(executionConfig);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("5", analysis.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("2", analysis.systemProperties().get(RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT));
    }

    @Test
    void negativeRetryPolicyStillFailsClosed() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "failOnFlakeCount", "-1");
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);
        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("negative <failOnFlakeCount>")),
                () -> String.join("; ", analysis.reasons()));
    }

    private Plugin plugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId("default-test");
        execution.setPhase("test");
        execution.setGoals(List.of("test"));
        plugin.addExecution(execution);
        return plugin;
    }

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
