package io.scenariomesh.maven;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestJvmResolverTest {
    @Test
    void unresolvedSimplePropertyIsNullLikeMavenPluginParameterEvaluation() {
        assertNull(TestJvmResolver.resolveProperty(
                "commons.surefire.java", new Properties(), new Properties(), new Properties()));
    }

    @Test
    void userThenSystemThenProjectPropertyPrecedenceMatchesMaven() {
        Properties project = properties("test.jvm", "project-java");
        Properties system = properties("test.jvm", "system-java");
        Properties user = properties("test.jvm", "user-java");

        assertEquals("user-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
        user.clear();
        assertEquals("system-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
        system.clear();
        assertEquals("project-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
    }

    private Properties properties(String... entries) {
        Properties properties = new Properties();
        for (int index = 0; index < entries.length; index += 2) {
            properties.setProperty(entries[index], entries[index + 1]);
        }
        return properties;
    }
}
