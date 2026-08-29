package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeJUnitEngineCompatibilityTest {
    private final FailsafeCompatibility compatibility = new FailsafeCompatibility();

    @Test
    void mapsEngineArraysPerFailsafeExecution() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includeJUnit5Engines");
        add(includes, "engine", "junit-jupiter");
        config.addChild(includes);
        Xpp3Dom excludes = new Xpp3Dom("excludeJUnit5Engines");
        add(excludes, "engine", "custom-engine");
        config.addChild(excludes);
        plugin.setConfiguration(config);

        FailsafeCompatibility.Analysis analysis = analyze(plugin);

        assertTrue(analysis.supported(), analysis.reason());
        var properties = analysis.executionPlans().get(0).systemProperties();
        assertEquals("junit-jupiter", properties.get(FailsafeCompatibility.INCLUDE_JUNIT5_ENGINES_PROPERTY));
        assertEquals("custom-engine", properties.get(FailsafeCompatibility.EXCLUDE_JUNIT5_ENGINES_PROPERTY));
    }

    @Test
    void vintageInclusionFailsClosed() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includeJUnit5Engines");
        add(includes, "engine", "junit-vintage");
        config.addChild(includes);
        plugin.setConfiguration(config);

        FailsafeCompatibility.Analysis analysis = analyze(plugin);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("JUnit Vintage"), analysis.reason());
    }

    private FailsafeCompatibility.Analysis analyze(Plugin plugin) {
        return compatibility.analyze(plugin, MavenExecutionPlan.through("verify").failsafeParticipation(plugin), ignored -> null);
    }

    private Plugin plugin() {
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
