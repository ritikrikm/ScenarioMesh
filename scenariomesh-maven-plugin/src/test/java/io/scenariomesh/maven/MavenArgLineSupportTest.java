package io.scenariomesh.maven;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenArgLineSupportTest {
    @Test
    void resolvesLatePropertiesAtExecutionTimeAndPreservesMavenPrecedence() {
        Properties project = properties("agent", "-javaagent:project.jar", "mode", "project");
        Properties system = properties("mode", "system");
        Properties user = properties("mode", "user");

        List<String> args = MavenArgLineSupport.merge(
                List.of("-Xms128m"),
                "@{agent} -Dmode=@{mode} -Dquoted=\"hello world\"",
                project, system, user);

        assertEquals(List.of(
                "-Xms128m", "-javaagent:project.jar", "-Dmode=user", "-Dquoted=hello world"), args);
    }

    @Test
    void missingLatePropertyIsReplacedByEmptyStringLikeSurefire() {
        List<String> args = MavenArgLineSupport.merge(
                List.of(), "@{missing} -Xmx512m", new Properties(), new Properties(), new Properties());
        assertEquals(List.of("-Xmx512m"), args);
    }

    @Test
    void malformedArgLineFailsClosedInsteadOfGuessingTokenization() {
        assertThrows(IllegalArgumentException.class, () -> MavenArgLineSupport.merge(
                List.of(), "-Dvalue=\"unterminated", new Properties(), new Properties(), new Properties()));
    }

    private Properties properties(String... entries) {
        Properties properties = new Properties();
        for (int index = 0; index < entries.length; index += 2) {
            properties.setProperty(entries[index], entries[index + 1]);
        }
        return properties;
    }
}
