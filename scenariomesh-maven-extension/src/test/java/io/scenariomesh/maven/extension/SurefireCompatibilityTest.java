package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireCompatibilityTest {
    private final SurefireCompatibility compatibility = new SurefireCompatibility();

    @Test
    void acceptsMavenGeneratedDefaultTestExecution() {
        Plugin plugin = pluginWith(defaultTestExecution());

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertFalse(analysis.explicitlySkipsTests());
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
    }

    @Test
    void acceptsExplicitUseModulePathFalse() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("useModulePath", "false"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertFalse(analysis.explicitlySkipsTests());
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
    }

    @Test
    void rejectsNonStandardExecution() {
        PluginExecution execution = new PluginExecution();
        execution.setId("company-smoke-tests");
        execution.setPhase("test");
        execution.addGoal("test");

        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(execution));

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("company-smoke-tests")));
    }

    @Test
    void rejectsChangedDefaultExecutionSemantics() {
        PluginExecution execution = defaultTestExecution();
        execution.setPhase("integration-test");

        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(execution));

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("non-standard execution")));
    }

    @Test
    void rejectsUnsupportedExecutionConfiguration() {
        PluginExecution execution = defaultTestExecution();
        execution.setConfiguration(configuration("includes", "**/SmokeTest.java"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(execution));

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("<includes>")));
    }

    @Test
    void rejectsUnknownFutureSurefireConfigurationByDefault() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("someFutureSurefireOption", "enabled"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("someFutureSurefireOption")));
    }

    @Test
    void rejectsUseModulePathTrueUntilModulePathExecutionIsSupported() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("useModulePath", "true"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("useModulePath")));
    }

    @Test
    void reportsLiteralSkipAsPassThroughSignal() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("skipTests", "true"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.explicitlySkipsTests());
    }

    @Test
    void rejectsDynamicSkipValueBecauseEquivalenceCannotBeProved() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("skipTests", "${company.skip.tests}"));

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertFalse(analysis.explicitlySkipsTests());
        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("non-literal <skipTests>")));
    }

    @Test
    void rejectsCustomSurefireProviderDependency() {
        Plugin plugin = pluginWith(defaultTestExecution());
        Dependency provider = new Dependency();
        provider.setGroupId("com.example");
        provider.setArtifactId("custom-surefire-provider");
        provider.setVersion("1.0");
        plugin.addDependency(provider);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);

        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("provider/plugin dependencies")));
    }

    private Plugin pluginWith(PluginExecution execution) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        plugin.addExecution(execution);
        return plugin;
    }

    private PluginExecution defaultTestExecution() {
        PluginExecution execution = new PluginExecution();
        execution.setId("default-test");
        execution.setPhase("test");
        execution.addGoal("test");
        return execution;
    }

    private Xpp3Dom configuration(String name, String value) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        root.addChild(child);
        return root;
    }
}
