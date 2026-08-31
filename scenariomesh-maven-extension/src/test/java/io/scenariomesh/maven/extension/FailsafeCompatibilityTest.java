package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.maven.selection.MavenSelectionCodec;
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
        var analysis = analyze(plugin, key -> null);
        assertTrue(analysis.supported(), analysis.reason());
        var plan = analysis.executionPlans().get(0);
        assertTrue(plan.excludeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/LoginIT$Nested.class")));
        assertTrue(plan.excludeClassNameRegexes().stream()
                .noneMatch(regex -> Pattern.matches(regex, "example/LoginIT.class")));
    }

    @Test
    void preservesJvmPropertiesFailureIgnoreAndDisabledRetryPolicy() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "argLine", "-Xmx512m -Dfile.encoding=UTF-8");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "language", "${language}");
        add(properties, "environment", "qa");
        config.addChild(properties);
        add(config, "testFailureIgnore", "true");
        add(config, "rerunFailingTestsCount", "${retry.count}");
        plugin.setConfiguration(config);

        var analysis = analyze(plugin, Map.of("language", "EN", "retry.count", "0")::get);
        var plan = analysis.executionPlans().get(0);
        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of("-Xmx512m", "-Dfile.encoding=UTF-8"), plan.jvmArgs());
        assertEquals("EN", plan.systemProperties().get("language"));
        assertEquals("qa", plan.systemProperties().get("environment"));
        assertEquals("0", plan.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("0", plan.systemProperties().get(RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT));
        assertTrue(plan.testFailureIgnore());
    }

    @Test
    void positiveRerunAndFlakeThresholdArePreservedInsteadOfRejected() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "2");
        add(config, "failOnFlakeCount", "3");
        plugin.setConfiguration(config);

        var analysis = analyze(plugin, key -> null);
        assertTrue(analysis.supported(), analysis.reason());
        var properties = analysis.executionPlans().get(0).systemProperties();
        assertEquals("2", properties.get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("3", properties.get(RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT));
    }

    @Test
    void executionLevelRetrySettingsOverridePluginLevelSettings() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom pluginConfig = new Xpp3Dom("configuration");
        add(pluginConfig, "rerunFailingTestsCount", "1");
        add(pluginConfig, "failOnFlakeCount", "1");
        plugin.setConfiguration(pluginConfig);
        Xpp3Dom executionConfig = new Xpp3Dom("configuration");
        add(executionConfig, "rerunFailingTestsCount", "4");
        add(executionConfig, "failOnFlakeCount", "2");
        add(executionConfig, "argLine", "-Xmx1g");
        plugin.getExecutions().get(0).setConfiguration(executionConfig);

        var plan = analyze(plugin, key -> null).executionPlans().get(0);
        assertEquals("4", plan.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("2", plan.systemProperties().get(RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT));
        assertEquals(List.of("-Xmx1g"), plan.jvmArgs());
    }

    @Test
    void unresolvedPropertyFailsSafe() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "${missing.retry.count}");
        plugin.setConfiguration(config);
        var analysis = analyze(plugin, key -> null);
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("unresolved Maven property"), analysis.reason());
    }

    @Test
    void negativeRetryPolicyFailsSafe() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "rerunFailingTestsCount", "-1");
        plugin.setConfiguration(config);
        var analysis = analyze(plugin, key -> null);
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("negative <rerunFailingTestsCount>"), analysis.reason());
    }

    @Test
    void stableLateArgLinePropertyIsResolvedWithoutVendorSpecificLogic() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "argLine", "@{instrumentation.args} -Dwork.dir=${work.dir}");
        plugin.setConfiguration(config);
        var participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        var analysis = compatibility.analyze(plugin, participation,
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
        var participation = MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        var analysis = compatibility.analyze(plugin, participation, key -> null, key -> null);
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("earlier lifecycle plugin may mutate it"), analysis.reason());
    }

    @Test
    void arbitraryDynamicSystemPropertiesRemainPreserved() {
        Plugin plugin = pluginWithExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "remote.grid.config", "${grid.config.path}");
        add(properties, "custom.feature.flag", "enabled");
        config.addChild(properties);
        plugin.setConfiguration(config);
        var plan = analyze(plugin, Map.of("grid.config.path", "/tmp/grid.yml")::get).executionPlans().get(0);
        assertEquals("/tmp/grid.yml", plan.systemProperties().get("remote.grid.config"));
        assertEquals("enabled", plan.systemProperties().get("custom.feature.flag"));
    }

    @Test
    void multipleFailsafeExecutionsRemainIndependentPlans() {
        Plugin plugin = pluginWithExecution();
        PluginExecution second = execution("regression-tests");
        Xpp3Dom firstConfig = new Xpp3Dom("configuration");
        firstConfig.addChild(patterns("includes", "**/SmokeIT.java"));
        add(firstConfig, "rerunFailingTestsCount", "1");
        plugin.getExecutions().get(0).setConfiguration(firstConfig);
        Xpp3Dom secondConfig = new Xpp3Dom("configuration");
        secondConfig.addChild(patterns("includes", "**/RegressionIT.java"));
        add(secondConfig, "rerunFailingTestsCount", "3");
        second.setConfiguration(secondConfig);
        plugin.addExecution(second);

        var analysis = analyze(plugin, key -> null);
        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(2, analysis.executionPlans().size());
        var first = analysis.executionPlans().get(0);
        var regression = analysis.executionPlans().get(1);
        assertEquals(List.of("**/SmokeIT.java"), MavenSelectionCodec.decode(
                first.systemProperties().get(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS)));
        assertEquals(List.of("**/RegressionIT.java"), MavenSelectionCodec.decode(
                regression.systemProperties().get(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS)));
        assertEquals("1", first.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
        assertEquals("3", regression.systemProperties().get(RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT));
    }

    private FailsafeCompatibility.Analysis analyze(Plugin plugin, java.util.function.Function<String, String> resolver) {
        return compatibility.analyze(plugin,
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin), resolver);
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
