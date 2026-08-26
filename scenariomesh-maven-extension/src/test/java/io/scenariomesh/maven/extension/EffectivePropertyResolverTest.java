package io.scenariomesh.maven.extension;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectivePropertyResolverTest {

    @Test
    void userPropertyOverridesSystemAndEffectiveProjectProperty() {
        MavenProject project = project();
        project.getProperties().setProperty("execution.mode", "profile-value");
        MavenSession session = session();
        session.getSystemProperties().setProperty("execution.mode", "system-value");
        session.getUserProperties().setProperty("execution.mode", "cli-value");

        EffectivePropertyResolver resolver = new EffectivePropertyResolver(session, project);

        assertEquals("cli-value", resolver.resolve("execution.mode"));
    }

    @Test
    void effectiveProjectPropertiesAreResolvedWhenNoInvocationOverrideExists() {
        MavenProject project = project();
        // MavenProject properties are the effective-model view at lifecycle-participant time;
        // this is where active POM/settings profile property contributions appear.
        project.getProperties().setProperty("remote.grid.config", "qa-grid.yml");

        EffectivePropertyResolver resolver = new EffectivePropertyResolver(session(), project);

        assertEquals("qa-grid.yml", resolver.resolve("remote.grid.config"));
        assertTrue(resolver.present("remote.grid.config"));
    }

    @Test
    void projectAliasesResolveFromEffectiveModel() {
        MavenProject project = project();
        project.setPackaging("jar");
        project.getBuild().setDirectory("target-custom");

        EffectivePropertyResolver resolver = new EffectivePropertyResolver(session(), project);

        assertEquals("jar", resolver.resolve("project.packaging"));
        assertEquals("target-custom", resolver.resolve("project.build.directory"));
    }

    @Test
    void lateReplacementDoesNotTrustMutableProjectProperty() {
        MavenProject project = project();
        project.getProperties().setProperty("agent.argLine", "-javaagent:mutable.jar");

        EffectivePropertyResolver resolver = new EffectivePropertyResolver(session(), project);

        assertEquals("-javaagent:mutable.jar", resolver.resolve("agent.argLine"));
        assertNull(resolver.resolveStableLate("agent.argLine"));
    }

    @Test
    void lateReplacementCanUseStableCliProperty() {
        MavenSession session = session();
        session.getUserProperties().setProperty("agent.argLine", "-javaagent:stable.jar");

        EffectivePropertyResolver resolver = new EffectivePropertyResolver(session, project());

        assertEquals("-javaagent:stable.jar", resolver.resolveStableLate("agent.argLine"));
    }

    private MavenSession session() {
        return new MavenSession(
                null,
                null,
                new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
    }

    private MavenProject project() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");
        model.setBuild(new Build());
        return new MavenProject(model);
    }
}
