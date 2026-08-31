package io.scenariomesh.maven.extension;

import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorSystemPropertyVersionCompatibilityTest {
    @TempDir
    Path tempDir;

    private final EffectiveExecutorSystemProperties builder = new EffectiveExecutorSystemProperties();

    @Test
    void systemPropertyVariablesFailClosedBeforeSurefire25() {
        Xpp3Dom configuration = configuration();
        Xpp3Dom variables = node("systemPropertyVariables");
        add(variables, "browser", "chrome");
        configuration.addChild(variables);

        EffectiveExecutorSystemProperties.Result result = builder.build(
                List.of(configuration), tempDir, ignored -> null, new Properties(), "2.4.3");

        assertFalse(result.supported());
        assertTrue(result.reason().contains("2.5"));
    }

    @Test
    void systemPropertiesFileFailClosedBeforeSurefire282() throws Exception {
        Files.writeString(tempDir.resolve("test.properties"), "browser=chrome\n");
        Xpp3Dom configuration = configuration();
        add(configuration, "systemPropertiesFile", "test.properties");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                List.of(configuration), tempDir, ignored -> null, new Properties(), "2.8.1");

        assertFalse(result.supported());
        assertTrue(result.reason().contains("2.8.2"));
    }

    @Test
    void promotionToggleFailClosedBeforeSurefire340() {
        Xpp3Dom configuration = configuration();
        add(configuration, "promoteUserPropertiesToSystemProperties", "false");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                List.of(configuration), tempDir, ignored -> null, new Properties(), "3.3.1");

        assertFalse(result.supported());
        assertTrue(result.reason().contains("3.4.0"));
    }

    @Test
    void currentMilestoneVersionsAcceptAllSupportedPropertySources() throws Exception {
        Files.writeString(tempDir.resolve("test.properties"), "fromFile=yes\n");
        Xpp3Dom configuration = configuration();
        add(configuration, "systemPropertiesFile", "test.properties");
        Xpp3Dom variables = node("systemPropertyVariables");
        add(variables, "browser", "chrome");
        configuration.addChild(variables);
        add(configuration, "promoteUserPropertiesToSystemProperties", "false");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                List.of(configuration), tempDir, ignored -> null, new Properties(), "3.6.0-M1");

        assertTrue(result.supported(), result.reason());
    }

    @Test
    void unresolvedPluginVersionFailsClosedWhenVersionSensitiveParameterIsUsed() {
        Xpp3Dom configuration = configuration();
        add(configuration, "promoteUserPropertiesToSystemProperties", "false");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                List.of(configuration), tempDir, ignored -> null, new Properties(), "${surefire.version}");

        assertFalse(result.supported());
        assertTrue(result.reason().contains("unresolved"));
    }

    private Xpp3Dom configuration() {
        return node("configuration");
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
