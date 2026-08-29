package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenForkLaunchConfigurationTest {
    private final MavenForkLaunchConfiguration compatibility = new MavenForkLaunchConfiguration();

    @TempDir Path projectDir;

    @Test
    void surefireExecutionOverridesPluginAssertionAndWorkingDirectory() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        Xpp3Dom global = new Xpp3Dom("configuration");
        add(global, "enableAssertions", "true");
        add(global, "workingDirectory", "global-work");
        plugin.setConfiguration(global);

        Xpp3Dom execution = new Xpp3Dom("configuration");
        add(execution, "enableAssertions", "false");
        add(execution, "workingDirectory", "execution-work");
        plugin.getExecutions().get(0).setConfiguration(execution);

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                ignored -> null);

        assertTrue(analysis.supported(), analysis.reason());
        MavenForkLaunchConfiguration.LaunchSettings settings = analysis.required("default-test");
        assertFalse(settings.enableAssertions());
        assertEquals(projectDir.resolve("execution-work").toAbsolutePath().normalize(), settings.workingDirectory());
    }

    @Test
    void commandLineEnableAssertionsOverridesPom() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        plugin.setConfiguration(configuration("enableAssertions", "true"));

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                key -> "enableAssertions".equals(key) ? "false" : null);

        assertTrue(analysis.supported(), analysis.reason());
        assertFalse(analysis.required("default-test").enableAssertions());
    }

    @Test
    void invalidCommandLineAssertionValueFailsClosedEvenWithoutExplicitPluginConfiguration() {
        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                null,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                key -> "enableAssertions".equals(key) ? "sometimes" : null);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("non-boolean"), analysis.reason());
    }

    @Test
    void defaultsMatchSurefireForkDefaults() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                ignored -> null);

        MavenForkLaunchConfiguration.LaunchSettings settings = analysis.required("default-test");
        assertTrue(settings.enableAssertions());
        assertTrue(settings.environmentVariables().isEmpty());
        assertTrue(settings.excludedEnvironmentVariables().isEmpty());
        assertNull(settings.workingDirectory());
    }

    @Test
    void failsafeKeepsExecutionLaunchSettingsIndependent() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-failsafe-plugin");
        plugin.addExecution(execution("first", configuration("enableAssertions", "false")));
        plugin.addExecution(execution("second", configuration("workingDirectory", "second-work")));

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.FAILSAFE,
                List.of("first", "second"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                ignored -> null);

        assertTrue(analysis.supported(), analysis.reason());
        assertFalse(analysis.required("first").enableAssertions());
        assertNull(analysis.required("first").workingDirectory());
        assertTrue(analysis.required("second").enableAssertions());
        assertEquals(projectDir.resolve("second-work").toAbsolutePath().normalize(),
                analysis.required("second").workingDirectory());
    }

    @Test
    void resolvesEnvironmentValuesWithoutLoggingTheirContents() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        Xpp3Dom root = new Xpp3Dom("configuration");
        Xpp3Dom environment = new Xpp3Dom("environmentVariables");
        add(environment, "API_TOKEN", "${secret.value}");
        root.addChild(environment);
        plugin.setConfiguration(root);

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> Map.of("project.basedir", projectDir.toString(), "secret.value", "sensitive-value").get(key),
                ignored -> null);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals("sensitive-value", analysis.required("default-test").environmentVariables().get("API_TOKEN"));
        assertTrue(analysis.reason() == null || !analysis.reason().contains("sensitive-value"));
    }

    @Test
    void preservesEmptyAndWhitespaceEnvironmentValuesExactly() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        Xpp3Dom root = new Xpp3Dom("configuration");
        Xpp3Dom environment = new Xpp3Dom("environmentVariables");
        add(environment, "EMPTY_VALUE", "");
        add(environment, "PADDED_VALUE", "  padded value  ");
        root.addChild(environment);
        plugin.setConfiguration(root);

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                ignored -> null);

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals("", analysis.required("default-test").environmentVariables().get("EMPTY_VALUE"));
        assertEquals("  padded value  ", analysis.required("default-test").environmentVariables().get("PADDED_VALUE"));
    }

    @Test
    void keepsConfiguredOverlayAndInheritedExclusionAsSeparateLaunchInstructions() {
        Plugin plugin = plugin("maven-surefire-plugin", "default-test");
        Xpp3Dom root = new Xpp3Dom("configuration");
        Xpp3Dom environment = new Xpp3Dom("environmentVariables");
        add(environment, "OVERLAY", "configured");
        root.addChild(environment);
        Xpp3Dom excluded = new Xpp3Dom("excludedEnvironmentVariables");
        add(excluded, "excludedEnvironmentVariable", "OVERLAY");
        root.addChild(excluded);
        plugin.setConfiguration(root);

        MavenForkLaunchConfiguration.Analysis analysis = compatibility.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                key -> "project.basedir".equals(key) ? projectDir.toString() : null,
                ignored -> null);

        assertTrue(analysis.supported(), analysis.reason());
        MavenForkLaunchConfiguration.LaunchSettings settings = analysis.required("default-test");
        assertEquals("configured", settings.environmentVariables().get("OVERLAY"));
        assertTrue(settings.excludedEnvironmentVariables().contains("OVERLAY"));
    }

    private Plugin plugin(String artifactId, String executionId) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId(artifactId);
        plugin.addExecution(execution(executionId, null));
        return plugin;
    }

    private PluginExecution execution(String id, Xpp3Dom configuration) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        if (configuration != null) execution.setConfiguration(configuration);
        return execution;
    }

    private Xpp3Dom configuration(String name, String value) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        add(root, name, value);
        return root;
    }

    private void add(Xpp3Dom root, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        root.addChild(child);
    }
}
