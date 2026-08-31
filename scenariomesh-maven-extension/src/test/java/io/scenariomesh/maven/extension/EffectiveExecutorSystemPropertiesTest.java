package io.scenariomesh.maven.extension;

import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveExecutorSystemPropertiesTest {
    @TempDir
    Path tempDir;

    private final EffectiveExecutorSystemProperties builder = new EffectiveExecutorSystemProperties();

    @Test
    void preservesSurefirePrecedenceAcrossLegacyFileVariablesAndUserProperties() throws Exception {
        Files.writeString(tempDir.resolve("surefire.properties"), "shared=file\nfileOnly=yes\n");
        Xpp3Dom configuration = dom("configuration");

        Xpp3Dom legacy = dom("systemProperties");
        Xpp3Dom legacyProperty = dom("property");
        child(legacyProperty, "name", "shared");
        child(legacyProperty, "value", "legacy");
        legacy.addChild(legacyProperty);
        configuration.addChild(legacy);

        child(configuration, "systemPropertiesFile", "surefire.properties");

        Xpp3Dom variables = dom("systemPropertyVariables");
        child(variables, "shared", "variables");
        child(variables, "variablesOnly", "yes");
        configuration.addChild(variables);

        Properties user = new Properties();
        user.setProperty("shared", "user");
        user.setProperty("userOnly", "yes");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                configuration, tempDir, key -> null, user);

        assertTrue(result.supported(), result.reason());
        assertEquals("user", result.properties().get("shared"));
        assertEquals("yes", result.properties().get("fileOnly"));
        assertEquals("yes", result.properties().get("variablesOnly"));
        assertEquals("yes", result.properties().get("userOnly"));
        assertEquals(EffectiveExecutorSystemProperties.Origin.MAVEN_USER_PROPERTY,
                result.origins().get("shared"));
    }

    @Test
    void disablingPromotionKeepsConfiguredValueAboveFileAndLegacySources() throws Exception {
        Files.writeString(tempDir.resolve("failsafe.properties"), "shared=file\n");
        Xpp3Dom configuration = dom("configuration");

        Xpp3Dom legacy = dom("systemProperties");
        Xpp3Dom property = dom("property");
        child(property, "name", "shared");
        child(property, "value", "legacy");
        legacy.addChild(property);
        configuration.addChild(legacy);
        child(configuration, "systemPropertiesFile", "failsafe.properties");
        Xpp3Dom variables = dom("systemPropertyVariables");
        child(variables, "shared", "variables");
        configuration.addChild(variables);
        child(configuration, "promoteUserPropertiesToSystemProperties", "false");

        Properties user = new Properties();
        user.setProperty("shared", "user");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                configuration, tempDir, key -> null, user);

        assertTrue(result.supported(), result.reason());
        assertEquals("variables", result.properties().get("shared"));
        assertEquals(EffectiveExecutorSystemProperties.Origin.SYSTEM_PROPERTY_VARIABLES,
                result.origins().get("shared"));
    }

    @Test
    void resolvesRelativeFileFromProjectBaseDirectoryAndMavenExpressions() throws Exception {
        Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(tempDir.resolve("config/test.properties"), "endpoint=https://example.test\n");
        Xpp3Dom configuration = dom("configuration");
        child(configuration, "systemPropertiesFile", "${props.file}");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                configuration,
                tempDir,
                key -> Map.of("props.file", "config/test.properties").get(key),
                new Properties());

        assertTrue(result.supported(), result.reason());
        assertEquals("https://example.test", result.properties().get("endpoint"));
        assertEquals(EffectiveExecutorSystemProperties.Origin.SYSTEM_PROPERTIES_FILE,
                result.origins().get("endpoint"));
    }

    @Test
    void failsClosedWhenExternalFileCannotBeResolved() {
        Xpp3Dom configuration = dom("configuration");
        child(configuration, "systemPropertiesFile", "missing.properties");

        EffectiveExecutorSystemProperties.Result result = builder.build(
                configuration, tempDir, key -> null, new Properties());

        assertFalse(result.supported());
        assertTrue(result.reason().contains("readable regular file"));
    }

    @Test
    void identifiesVmStartupOnlyPropertiesInsteadOfPretendingProviderMutationIsEnough() {
        Xpp3Dom configuration = dom("configuration");
        Xpp3Dom variables = dom("systemPropertyVariables");
        child(variables, "file.encoding", "UTF-8");
        configuration.addChild(variables);

        EffectiveExecutorSystemProperties.Result result = builder.build(
                configuration, tempDir, key -> null, new Properties());

        assertTrue(result.supported(), result.reason());
        assertTrue(result.vmStartupOnlyProperties().contains("file.encoding"));
    }

    private Xpp3Dom dom(String name) {
        return new Xpp3Dom(name);
    }

    private void child(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = dom(name);
        child.setValue(value);
        parent.addChild(child);
    }
}
