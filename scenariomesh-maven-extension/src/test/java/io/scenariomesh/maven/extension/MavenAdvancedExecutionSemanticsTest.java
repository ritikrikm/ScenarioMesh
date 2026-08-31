package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MavenAdvancedExecutionSemanticsTest {
    @Test
    void preservesSeededRandomRunOrderPerExecution() {
        Plugin plugin = pluginWithExecution("fast", config("runOrder", "random"));
        MavenRunOrderConfiguration.Analysis analysis = new MavenRunOrderConfiguration().analyze(
                plugin, ProjectCompatibilityDetector.ExecutorKind.SUREFIRE, List.of("fast"),
                key -> "/tmp/project", key -> "surefire.runOrder.random.seed".equals(key) ? "12345" : null);
        assertTrue(analysis.supported(), analysis.reason());
        MavenRunOrderConfiguration.Settings settings = analysis.required("fast");
        assertEquals("random", settings.mode());
        assertEquals(12345L, settings.randomSeed());
        assertEquals("12345", settings.internalProperties().get("scenariomesh.internal.maven.runOrder.randomSeed"));
    }

    @Test
    void rejectsStatefulRunOrderUntilStatisticsLifecycleIsOwned() {
        Plugin plugin = pluginWithExecution("balanced", config("runOrder", "balanced"));
        MavenRunOrderConfiguration.Analysis analysis = new MavenRunOrderConfiguration().analyze(
                plugin, ProjectCompatibilityDetector.ExecutorKind.SUREFIRE, List.of("balanced"),
                key -> "/tmp/project", key -> null);
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("persistent .surefire-* statistics"));
    }

    @Test
    void acceptsKnownJUnitPlatformEnginePluginDependency() {
        Plugin plugin = new Plugin();
        Dependency dependency = dependency("org.junit.jupiter", "junit-jupiter-engine", "6.0.0");
        plugin.setDependencies(List.of(dependency));
        MavenProviderDependencyCompatibility.Analysis analysis = new MavenProviderDependencyCompatibility().analyze(plugin);
        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of(dependency), analysis.engineDependencies());
        assertTrue(analysis.providerIntents().isEmpty());
    }

    @Test
    void classifiesKnownSurefireProviderSelectorWithoutAddingItToTargetClasspath() {
        Plugin plugin = new Plugin();
        plugin.setDependencies(List.of(dependency(
                "org.apache.maven.surefire", "surefire-testng", "3.5.4")));
        MavenProviderDependencyCompatibility.Analysis analysis = new MavenProviderDependencyCompatibility().analyze(plugin);
        assertTrue(analysis.supported(), analysis.reason());
        assertTrue(analysis.engineDependencies().isEmpty());
        assertEquals(Set.of("testng"), analysis.providerIntents());
    }

    @Test
    void rejectsUnknownCustomProviderDependency() {
        Plugin plugin = new Plugin();
        plugin.setDependencies(List.of(dependency("com.acme", "custom-surefire-provider", "1.0")));
        MavenProviderDependencyCompatibility.Analysis analysis = new MavenProviderDependencyCompatibility().analyze(plugin);
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("unregistered provider/plugin extension"));
    }

    @Test
    void resolvesClasspathSettingsIndependentlyForMultipleSurefireExecutions() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        plugin.addExecution(execution("one", config("workingDirectory", "/tmp/one")));
        plugin.addExecution(execution("two", config("additionalClasspathElements", null,
                child("additionalClasspathElement", "/tmp/two.jar"))));
        MavenExecutorClasspathConfiguration.Analysis analysis = new MavenExecutorClasspathConfiguration().analyze(
                plugin, ProjectCompatibilityDetector.ExecutorKind.SUREFIRE, List.of("one", "two"),
                key -> null, key -> null, dependencies -> List.of());
        assertTrue(analysis.supported(), analysis.reason());
        assertTrue(analysis.required("one").additionalClasspathElements().isEmpty());
        assertEquals(List.of("/tmp/two.jar"), analysis.required("two").additionalClasspathElements());
    }

    private Plugin pluginWithExecution(String id, Xpp3Dom configuration) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        plugin.addExecution(execution(id, configuration));
        return plugin;
    }

    private PluginExecution execution(String id, Xpp3Dom configuration) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase("test");
        execution.addGoal("test");
        execution.setConfiguration(configuration);
        return execution;
    }

    private Dependency dependency(String groupId, String artifactId, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        return dependency;
    }

    private Xpp3Dom config(String name, String value, Xpp3Dom... children) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom node = new Xpp3Dom(name);
        if (value != null) node.setValue(value);
        for (Xpp3Dom child : children) node.addChild(child);
        configuration.addChild(node);
        return configuration;
    }

    private Xpp3Dom config(String name, String value) { return config(name, value, new Xpp3Dom[0]); }

    private Xpp3Dom child(String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        return child;
    }
}
