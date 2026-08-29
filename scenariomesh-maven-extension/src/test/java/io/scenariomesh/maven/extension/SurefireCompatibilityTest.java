package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void defaultIncludesMirrorSurefireClassNamingBoundary() {
        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(defaultTestExecution()));
        assertTrue(matchesAny(analysis, "example.TestPayment"));
        assertTrue(matchesAny(analysis, "example.PaymentTest"));
        assertTrue(matchesAny(analysis, "example.PaymentTests"));
        assertTrue(matchesAny(analysis, "example.PaymentTestCase"));
        assertFalse(matchesAny(analysis, "example.PaymentHelper"));
        assertFalse(matchesAny(analysis, "example.ProductionService"));
    }

    @Test
    void defaultExcludesMatchSurefireInnerClassBoundary() {
        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(defaultTestExecution()));
        assertTrue(analysis.excludeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/PaymentTest$Nested.class")));
        assertTrue(analysis.excludeClassNameRegexes().stream()
                .noneMatch(regex -> Pattern.matches(regex, "example/PaymentTest.class")));
    }

    @Test
    void nativeForkAndThreadParallelismIsReplacedByScenarioMesh() {
        Plugin plugin = pluginWith(defaultTestExecution());
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "forkCount", "4");
        add(config, "reuseForks", "true");
        add(config, "parallel", "classes");
        add(config, "threadCount", "4");
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
    }

    @Test
    void knownButUnsupportedSemanticCapabilityIsNamedExplicitly() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("groups", "smoke"));
        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin);
        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("framework-group-selection")));
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
    void reproducesSurefireIncludesFromExecutionConfiguration() {
        PluginExecution execution = defaultTestExecution();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includes");
        add(includes, "include", "**/Smoke*Test.java");
        config.addChild(includes);
        execution.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(pluginWith(execution));
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertTrue(analysis.includeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/SmokeCheckoutTest.class")));
        assertFalse(analysis.includeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/CheckoutTest.class")));
    }

    @Test
    void resolvesMavenPropertiesInsideSurefireIncludes() {
        Plugin plugin = pluginWith(defaultTestExecution());
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includes");
        add(includes, "include", "${company.test.pattern}");
        config.addChild(includes);
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(
                plugin, Map.of("company.test.pattern", "**/*ContractTest.java")::get);
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertTrue(analysis.includeClassNameRegexes().stream()
                .anyMatch(regex -> Pattern.matches(regex, "example/PaymentContractTest.class")));
    }

    @Test
    void reproducesSurefireSystemPropertyVariables() {
        Plugin plugin = pluginWith(defaultTestExecution());
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "baseUrl", "${test.baseUrl}");
        add(properties, "browser", "chrome");
        config.addChild(properties);
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(
                plugin, Map.of("test.baseUrl", "https://example.test")::get);
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
        assertEquals("https://example.test", analysis.systemProperties().get("baseUrl"));
        assertEquals("chrome", analysis.systemProperties().get("browser"));
    }

    @Test
    void unresolvedSurefireSystemPropertyFailsClosed() {
        Plugin plugin = pluginWith(defaultTestExecution());
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom properties = new Xpp3Dom("systemPropertyVariables");
        add(properties, "baseUrl", "${missing.baseUrl}");
        config.addChild(properties);
        plugin.setConfiguration(config);

        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, ignored -> null);
        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("unresolved Maven property")));
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
    void resolvesDynamicSkipFromEffectiveMavenProperties() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("skipTests", "${company.skip.tests}"));
        Map<String, String> values = Map.of("company.skip.tests", "false");
        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, values::get);
        assertFalse(analysis.explicitlySkipsTests());
        assertTrue(analysis.reasons().isEmpty(), () -> String.join("; ", analysis.reasons()));
    }

    @Test
    void resolvedDynamicSkipTrueRemainsPassThroughSignal() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("skipTests", "${company.skip.tests}"));
        Map<String, String> values = Map.of("company.skip.tests", "true");
        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, values::get);
        assertTrue(analysis.explicitlySkipsTests());
    }

    @Test
    void unresolvedDynamicSkipStillFailsClosed() {
        Plugin plugin = pluginWith(defaultTestExecution());
        plugin.setConfiguration(configuration("skipTests", "${company.skip.tests}"));
        SurefireCompatibility.Analysis analysis = compatibility.analyze(plugin, ignored -> null);
        assertFalse(analysis.explicitlySkipsTests());
        assertTrue(analysis.reasons().stream().anyMatch(reason -> reason.contains("unresolved Maven property")));
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

    private boolean matchesAny(SurefireCompatibility.Analysis analysis, String className) {
        return analysis.includeClassNameRegexes().stream().anyMatch(regex -> Pattern.matches(regex, className));
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
        add(root, name, value);
        return root;
    }

    private void add(Xpp3Dom root, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        root.addChild(child);
    }
}
