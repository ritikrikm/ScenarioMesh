package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireExecutionSemanticsTest {
    private final SurefireCompatibility compatibility = new SurefireCompatibility();

    @Test
    void preservesArgLineForLateExecutionTimeResolution() {
        Plugin plugin = plugin();
        plugin.setConfiguration(configuration("argLine", "-Xmx512m @{jacocoArgLine} -Dmode=${mode}"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(
                plugin, Map.of("mode", "smoke")::get, ignored -> null);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("-Xmx512m @{jacocoArgLine} -Dmode=smoke", analysis.argLine());
    }

    @Test
    void userArgLineOverridesPomArgLine() {
        Plugin plugin = plugin();
        plugin.setConfiguration(configuration("argLine", "-Xmx512m"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(
                plugin, ignored -> null, key -> "argLine".equals(key) ? "-Xms256m" : null);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("-Xms256m", analysis.argLine());
    }

    @Test
    void refusesForkNumberUntilScenarioMeshCanProvePerForkEquivalence() {
        Plugin plugin = plugin();
        plugin.setConfiguration(configuration("argLine", "-Dfork=${surefire.forkNumber}"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, ignored -> null, ignored -> null);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("surefire.forkNumber")));
    }

    @Test
    void carriesSurefireFailureAndZeroTestPolicies() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "testFailureIgnore", "true");
        add(config, "failIfNoTests", "true");
        add(config, "failIfNoSpecifiedTests", "false");
        add(config, "promoteUserPropertiesToSystemProperties", "false");
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, ignored -> null, ignored -> null);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertTrue(analysis.testFailureIgnore());
        assertTrue(analysis.failIfNoTests());
        assertFalse(analysis.failIfNoSpecifiedTests());
        assertFalse(analysis.promoteUserPropertiesToSystemProperties());
    }

    @Test
    void userPropertiesOverrideConfiguredSurefireBooleanParameters() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "testFailureIgnore", "false");
        add(config, "failIfNoTests", "false");
        add(config, "failIfNoSpecifiedTests", "true");
        plugin.setConfiguration(config);
        Map<String, String> user = Map.of(
                "maven.test.failure.ignore", "true",
                "failIfNoTests", "true",
                "surefire.failIfNoSpecifiedTests", "false");

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, ignored -> null, user::get);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertTrue(analysis.testFailureIgnore());
        assertTrue(analysis.failIfNoTests());
        assertFalse(analysis.failIfNoSpecifiedTests());
    }

    @Test
    void mergesLegacyFileAndVariableSystemPropertiesInDocumentedOrder(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("surefire.properties"), "source=file\nfileOnly=yes\n");

        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom legacy = new Xpp3Dom("systemProperties");
        legacy.addChild(legacyProperty("source", "legacy"));
        legacy.addChild(legacyProperty("legacyOnly", "yes"));
        config.addChild(legacy);
        add(config, "systemPropertiesFile", "surefire.properties");
        Xpp3Dom variables = new Xpp3Dom("systemPropertyVariables");
        add(variables, "source", "variables");
        add(variables, "variableOnly", "yes");
        config.addChild(variables);
        plugin.setConfiguration(config);

        Map<String, String> effective = new HashMap<>();
        effective.put("project.basedir", tempDir.toString());
        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, effective::get, ignored -> null);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("variables", analysis.systemProperties().get("source"));
        assertEquals("yes", analysis.systemProperties().get("legacyOnly"));
        assertEquals("yes", analysis.systemProperties().get("fileOnly"));
        assertEquals("yes", analysis.systemProperties().get("variableOnly"));
    }

    @Test
    void missingSystemPropertiesFileFailsClosed(@TempDir Path tempDir) {
        Plugin plugin = plugin();
        plugin.setConfiguration(configuration("systemPropertiesFile", "missing.properties"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(
                plugin, Map.of("project.basedir", tempDir.toString())::get, ignored -> null);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("does not exist")));
    }

    private Plugin plugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId("default-test");
        execution.setPhase("test");
        execution.addGoal("test");
        plugin.addExecution(execution);
        return plugin;
    }

    private Xpp3Dom configuration(String name, String value) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        add(root, name, value);
        return root;
    }

    private Xpp3Dom legacyProperty(String name, String value) {
        Xpp3Dom property = new Xpp3Dom("property");
        add(property, "name", name);
        add(property, "value", value);
        return property;
    }

    private void add(Xpp3Dom root, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        root.addChild(child);
    }
}
