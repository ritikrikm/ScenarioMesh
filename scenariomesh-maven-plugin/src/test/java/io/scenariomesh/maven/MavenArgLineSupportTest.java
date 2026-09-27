package io.scenariomesh.maven;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavenArgLineSupportTest {
    @Test
    void resolvesLatePropertiesFromMavenModelAtExecutionTime() {
        Properties model = properties("agent", "-javaagent:project.jar", "mode", "project");

        List<String> args = MavenArgLineSupport.merge(
                List.of("-Xms128m"),
                "@{agent} -Dmode=@{mode} -Dquoted=\"hello world\"",
                model);

        assertEquals(List.of(
                "-Xms128m", "-javaagent:project.jar", "-Dmode=project", "-Dquoted=hello world"), args);
    }

    @Test
    void sessionPropertiesDoNotOverrideSurefireLateModelProperties() {
        Model model = new Model();
        model.setProperties(properties("mode", "model"));
        MavenProject project = new MavenProject(model);
        MavenSession session = new MavenSession(
                null, null, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
        session.getSystemProperties().setProperty("mode", "system");
        session.getUserProperties().setProperty("mode", "user");

        assertEquals(
                List.of("-Dmode=model"),
                MavenArgLineSupport.merge(List.of(), "-Dmode=@{mode}", project, session));
    }

    @Test
    void unresolvedLatePropertyRemainsLiteralLikeSurefire() {
        List<String> args = MavenArgLineSupport.merge(
                List.of(), "@{missing} -Xmx512m", new Properties());
        assertEquals(List.of("@{missing}", "-Xmx512m"), args);
    }

    @Test
    void normalizesWhitespaceAndTokenizesQuotedValuesLikeSurefire() {
        List<String> args = MavenArgLineSupport.merge(
                List.of(),
                "-Done=1\t-Dtwo=\"hello world\"\n-Xmx256m",
                new Properties());

        assertEquals(List.of("-Done=1", "-Dtwo=hello world", "-Xmx256m"), args);
    }

    @Test
    void preservesSharedUtilsEscapedQuoteSemanticsUsedBySurefire() {
        String argLine = "-Dvalue=\\\"hello\\\"";
        assertEquals(List.of(argLine), MavenArgLineSupport.tokenizeLikeSurefire(argLine));
    }

    @Test
    void malformedArgLineFailsClosedInsteadOfGuessingTokenization() {
        assertThrows(IllegalArgumentException.class, () -> MavenArgLineSupport.merge(
                List.of(), "-Dvalue=\"unterminated", new Properties()));
    }

    private Properties properties(String... entries) {
        Properties properties = new Properties();
        for (int index = 0; index < entries.length; index += 2) {
            properties.setProperty(entries[index], entries[index + 1]);
        }
        return properties;
    }
}
