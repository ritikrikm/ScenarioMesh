package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalExecutorSystemPropertyCompatibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void surefireOwnsLegacyFileVariablesAndMavenUserPrecedence() throws Exception {
        Files.writeString(tempDir.resolve("surefire.properties"),
                "shared=file\nfileOnly=yes\n");

        Plugin surefire = surefire();
        Xpp3Dom configuration = configuration();
        configuration.addChild(legacyProperty("shared", "legacy"));
        add(configuration, "systemPropertiesFile", "surefire.properties");
        Xpp3Dom variables = node("systemPropertyVariables");
        add(variables, "shared", "variables");
        add(variables, "variableOnly", "yes");
        configuration.addChild(variables);
        surefire.setConfiguration(configuration);

        Properties user = new Properties();
        user.setProperty("shared", "user");
        user.setProperty("userOnly", "yes");

        SurefireCompatibility.Analysis analysis = new SurefireCompatibility().analyze(
                surefire,
                Map.of("project.basedir", tempDir.toString())::get,
                user);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("user", analysis.systemProperties().get("shared"));
        assertEquals("yes", analysis.systemProperties().get("fileOnly"));
        assertEquals("yes", analysis.systemProperties().get("variableOnly"));
        assertEquals("yes", analysis.systemProperties().get("userOnly"));
    }

    @Test
    void surefirePromotionCanBeDisabledWithoutDroppingConfiguredProperties() {
        Plugin surefire = surefire();
        Xpp3Dom configuration = configuration();
        Xpp3Dom variables = node("systemPropertyVariables");
        add(variables, "shared", "configured");
        configuration.addChild(variables);
        add(configuration, "promoteUserPropertiesToSystemProperties", "false");
        surefire.setConfiguration(configuration);

        Properties user = new Properties();
        user.setProperty("shared", "user");
        user.setProperty("userOnly", "no");

        SurefireCompatibility.Analysis analysis = new SurefireCompatibility().analyze(
                surefire,
                Map.of("project.basedir", tempDir.toString())::get,
                user);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("configured", analysis.systemProperties().get("shared"));
        assertFalse(analysis.systemProperties().containsKey("userOnly"));
    }

    @Test
    void failsafeBuildsPropertiesIndependentlyForEachActiveExecution() throws Exception {
        Files.writeString(tempDir.resolve("plugin.properties"), "source=plugin-file\npluginOnly=yes\n");
        Files.writeString(tempDir.resolve("execution.properties"), "source=execution-file\nexecutionOnly=yes\n");

        Plugin failsafe = new Plugin();
        failsafe.setGroupId("org.apache.maven.plugins");
        failsafe.setArtifactId("maven-failsafe-plugin");

        Xpp3Dom pluginConfiguration = configuration();
        add(pluginConfiguration, "systemPropertiesFile", "plugin.properties");
        Xpp3Dom pluginVariables = node("systemPropertyVariables");
        add(pluginVariables, "source", "plugin-variable");
        add(pluginVariables, "variableOnly", "yes");
        pluginConfiguration.addChild(pluginVariables);
        failsafe.setConfiguration(pluginConfiguration);

        PluginExecution execution = new PluginExecution();
        execution.setId("default-it");
        execution.addGoal("integration-test");
        Xpp3Dom executionConfiguration = configuration();
        add(executionConfiguration, "systemPropertiesFile", "execution.properties");
        Xpp3Dom executionVariables = node("systemPropertyVariables");
        add(executionVariables, "source", "execution-variable");
        executionConfiguration.addChild(executionVariables);
        execution.setConfiguration(executionConfiguration);
        failsafe.addExecution(execution);

        Properties user = new Properties();
        user.setProperty("source", "user");
        user.setProperty("userOnly", "yes");

        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(failsafe);
        FailsafeCompatibility.Analysis analysis = new FailsafeCompatibility().analyze(
                failsafe,
                participation,
                Map.of("project.basedir", tempDir.toString())::get,
                Map.of("project.basedir", tempDir.toString())::get,
                user);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(1, analysis.executionPlans().size());
        Map<String, String> properties = analysis.executionPlans().get(0).systemProperties();
        assertEquals("user", properties.get("source"));
        assertEquals("yes", properties.get("executionOnly"));
        assertFalse(properties.containsKey("pluginOnly"),
                "execution-level scalar systemPropertiesFile must replace plugin-level file");
        assertEquals("yes", properties.get("variableOnly"),
                "map-like systemPropertyVariables retain non-overridden plugin entries");
        assertEquals("yes", properties.get("userOnly"));
    }

    @Test
    void missingSystemPropertiesFileRemainsFailClosed() {
        Plugin surefire = surefire();
        Xpp3Dom configuration = configuration();
        add(configuration, "systemPropertiesFile", "does-not-exist.properties");
        surefire.setConfiguration(configuration);

        SurefireCompatibility.Analysis analysis = new SurefireCompatibility().analyze(
                surefire,
                Map.of("project.basedir", tempDir.toString())::get,
                new Properties());

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("system-property configuration")));
    }

    private Plugin surefire() {
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

    private Xpp3Dom configuration() {
        return node("configuration");
    }

    private Xpp3Dom legacyProperty(String name, String value) {
        Xpp3Dom systemProperties = node("systemProperties");
        Xpp3Dom property = node("property");
        add(property, "name", name);
        add(property, "value", value);
        systemProperties.addChild(property);
        return systemProperties;
    }

    private Xpp3Dom node(String name) {
        return new Xpp3Dom(name);
    }

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = node(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
