package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExecutorClasspathConfigurationTest {
    private final MavenExecutorClasspathConfiguration configuration = new MavenExecutorClasspathConfiguration();

    @Test
    void resolvesDependencyTreeAfterLiteralAdditionalElementsAndPreservesDependencyModel() {
        Plugin plugin = plugin(additionalDependency("org.example", "root", "${dep.version}", "test-jar", "tests"));
        AtomicReference<List<org.apache.maven.model.Dependency>> captured = new AtomicReference<>();

        var analysis = configuration.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                name -> Map.of("dep.version", "1.2.3").get(name),
                ignored -> null,
                dependencies -> {
                    captured.set(dependencies);
                    return List.of("/repo/root-1.2.3-tests.jar", "/repo/transitive.jar");
                });

        assertTrue(analysis.supported(), analysis.reason());
        var settings = analysis.required("default-test");
        assertEquals(List.of("/repo/root-1.2.3-tests.jar", "/repo/transitive.jar"), settings.additionalClasspathElements());
        assertEquals(1, captured.get().size());
        var dependency = captured.get().get(0);
        assertEquals("org.example", dependency.getGroupId());
        assertEquals("root", dependency.getArtifactId());
        assertEquals("1.2.3", dependency.getVersion());
        assertEquals("test-jar", dependency.getType());
        assertEquals("tests", dependency.getClassifier());
        assertEquals(1, dependency.getExclusions().size());
        assertEquals("org.unwanted", dependency.getExclusions().get(0).getGroupId());
    }

    @Test
    void failsafeResolvesEachStandardExecutionWithItsEffectiveDependencyConfiguration() {
        Plugin plugin = new Plugin();
        plugin.setConfiguration(configuration(additionalDependency("org.example", "shared", "1.0", null, null)));
        plugin.addExecution(execution("first", additionalDependency("org.example", "first", "2.0", null, null)));
        plugin.addExecution(execution("second", additionalDependency("org.example", "second", "3.0", null, null)));
        List<List<String>> resolvedRoots = new ArrayList<>();

        var analysis = configuration.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.FAILSAFE,
                List.of("first", "second"),
                ignored -> null,
                ignored -> null,
                dependencies -> {
                    resolvedRoots.add(dependencies.stream().map(org.apache.maven.model.Dependency::getArtifactId).toList());
                    return dependencies.stream().map(d -> "/repo/" + d.getArtifactId() + ".jar").toList();
                });

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of("/repo/shared.jar", "/repo/first.jar"), analysis.required("first").additionalClasspathElements());
        assertEquals(List.of("/repo/shared.jar", "/repo/second.jar"), analysis.required("second").additionalClasspathElements());
        assertEquals(List.of(List.of("shared", "first"), List.of("shared", "second")), resolvedRoots);
    }

    @Test
    void requiresExplicitVersionInsteadOfApplyingProjectDependencyManagement() {
        Plugin plugin = plugin(additionalDependency("org.example", "root", null, null, null));

        var analysis = configuration.analyze(
                plugin,
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                ignored -> null,
                ignored -> null,
                dependencies -> List.of("/should/not/resolve"));

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("requires groupId, artifactId and version"), analysis.reason());
    }

    @Test
    void unsupportedAdditionalDependencyScopeFailsClosed() {
        Xpp3Dom dependency = additionalDependency("org.example", "root", "1.0", null, null);
        add(dependency, "scope", "test");

        var analysis = configuration.analyze(
                plugin(dependency),
                ProjectCompatibilityDetector.ExecutorKind.SUREFIRE,
                List.of("default-test"),
                ignored -> null,
                ignored -> null,
                dependencies -> List.of());

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("outside Surefire's effective compile/runtime"), analysis.reason());
    }

    private Plugin plugin(Xpp3Dom dependency) {
        Plugin plugin = new Plugin();
        plugin.setConfiguration(configuration(dependency));
        return plugin;
    }

    private PluginExecution execution(String id, Xpp3Dom dependency) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setConfiguration(configuration(dependency));
        return execution;
    }

    private Xpp3Dom configuration(Xpp3Dom dependency) {
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom dependencies = new Xpp3Dom("additionalClasspathDependencies");
        dependencies.addChild(dependency);
        config.addChild(dependencies);
        return config;
    }

    private Xpp3Dom additionalDependency(String groupId, String artifactId, String version,
                                         String type, String classifier) {
        Xpp3Dom dependency = new Xpp3Dom("additionalClasspathDependency");
        add(dependency, "groupId", groupId);
        add(dependency, "artifactId", artifactId);
        if (version != null) add(dependency, "version", version);
        if (type != null) add(dependency, "type", type);
        if (classifier != null) add(dependency, "classifier", classifier);
        Xpp3Dom exclusions = new Xpp3Dom("exclusions");
        Xpp3Dom exclusion = new Xpp3Dom("exclusion");
        add(exclusion, "groupId", "org.unwanted");
        add(exclusion, "artifactId", "bad-lib");
        exclusions.addChild(exclusion);
        dependency.addChild(exclusions);
        return dependency;
    }

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
