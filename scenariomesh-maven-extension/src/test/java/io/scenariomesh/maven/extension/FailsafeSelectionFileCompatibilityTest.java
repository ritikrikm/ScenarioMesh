package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeSelectionFileCompatibilityTest {
    private final FailsafeCompatibility compatibility = new FailsafeCompatibility();

    @TempDir
    Path tempDir;

    @Test
    void appendsIncludesFileToInlineFailsafeIncludes() throws Exception {
        Files.writeString(tempDir.resolve("includes.txt"), "# smoke selection\n\n**/RegressionIT.java\n");
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includes");
        add(includes, "include", "**/SmokeIT.java");
        config.addChild(includes);
        add(config, "includesFile", "includes.txt");
        plugin.setConfiguration(config);

        FailsafeCompatibility.Analysis analysis = analyze(plugin);

        assertTrue(analysis.supported(), analysis.reason());
        List<String> regexes = analysis.executionPlans().get(0).includeClassNameRegexes();
        assertTrue(matches(regexes, "example/SmokeIT.class"));
        assertTrue(matches(regexes, "example/RegressionIT.class"));
        assertFalse(matches(regexes, "example/OtherIT.class"));
    }

    @Test
    void appendsExcludesFileToInlineFailsafeExcludes() throws Exception {
        Files.writeString(tempDir.resolve("excludes.txt"), "**/*DatabaseIT.java\n");
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom excludes = new Xpp3Dom("excludes");
        add(excludes, "exclude", "**/*SlowIT.java");
        config.addChild(excludes);
        add(config, "excludesFile", "${selection.file}");
        plugin.setConfiguration(config);

        Map<String, String> properties = Map.of(
                "project.basedir", tempDir.toString(),
                "selection.file", "excludes.txt");
        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        FailsafeCompatibility.Analysis analysis = compatibility.analyze(plugin, participation, properties::get);

        assertTrue(analysis.supported(), analysis.reason());
        List<String> regexes = analysis.executionPlans().get(0).excludeClassNameRegexes();
        assertTrue(matches(regexes, "example/CheckoutSlowIT.class"));
        assertTrue(matches(regexes, "example/CheckoutDatabaseIT.class"));
    }

    @Test
    void missingFailsafeSelectionFileFailsClosed() {
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "includesFile", "missing.txt");
        plugin.setConfiguration(config);

        FailsafeCompatibility.Analysis analysis = analyze(plugin);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("readable regular file"), analysis.reason());
    }

    @Test
    void methodSelectorInFailsafeSelectionFileRemainsNativeMaven() throws Exception {
        Files.writeString(tempDir.resolve("includes.txt"), "CheckoutIT#createsOrder\n");
        Plugin plugin = plugin();
        Xpp3Dom config = new Xpp3Dom("configuration");
        add(config, "includesFile", "includes.txt");
        plugin.setConfiguration(config);

        FailsafeCompatibility.Analysis analysis = analyze(plugin);

        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("method selectors"), analysis.reason());
    }

    private FailsafeCompatibility.Analysis analyze(Plugin plugin) {
        MavenExecutionPlan.PluginParticipation participation =
                MavenExecutionPlan.through("verify").failsafeParticipation(plugin);
        return compatibility.analyze(
                plugin,
                participation,
                key -> "project.basedir".equals(key) ? tempDir.toString() : null);
    }

    private boolean matches(List<String> regexes, String classFile) {
        return regexes.stream().anyMatch(regex -> Pattern.matches(regex, classFile));
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
