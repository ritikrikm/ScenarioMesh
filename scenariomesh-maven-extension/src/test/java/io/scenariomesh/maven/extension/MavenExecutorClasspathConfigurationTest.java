package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

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
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom dependencies = new Xpp3Dom("additionalClasspathDependencies");
        dependencies.addChild(dependency);
        config.addChild(dependencies);
        Plugin plugin = new Plugin();
        plugin.setConfiguration(config);
        return plugin;
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
