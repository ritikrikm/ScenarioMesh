package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireJUnitEngineCompatibilityTest {
    private final SurefireCompatibility compatibility = new SurefireCompatibility();

    @Test
    void mapsConfiguredEngineArraysToSurefireProviderProperties() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includeJUnit5Engines");
        add(includes, "engine", "junit-jupiter");
        add(includes, "engine", "cucumber");
        config.addChild(includes);
        Xpp3Dom excludes = new Xpp3Dom("excludeJUnit5Engines");
        add(excludes, "engine", "custom-engine");
        config.addChild(excludes);
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("junit-jupiter,cucumber",
                analysis.systemProperties().get(SurefireCompatibility.INCLUDE_JUNIT5_ENGINES_PROPERTY));
        assertEquals("custom-engine",
                analysis.systemProperties().get(SurefireCompatibility.EXCLUDE_JUNIT5_ENGINES_PROPERTY));
    }

    @Test
    void vintageInclusionFailsClosedUntilP1GenericJUnit4IsProven() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includeJUnit5Engines");
        add(includes, "engine", "junit-vintage");
        config.addChild(includes);
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("JUnit Vintage")));
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

    private void add(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
