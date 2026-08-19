package io.scenariomesh.maven.extension;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

import java.util.Locale;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "scenariomesh")
public final class ScenarioMeshLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private static final String GROUP_ID = "io.scenariomesh";
    private static final String PLUGIN_ARTIFACT_ID = "scenariomesh-maven-plugin";
    private static final String VERSION = "0.1.0-SNAPSHOT";

    private final ProjectCompatibilityDetector compatibilityDetector = new ProjectCompatibilityDetector();

    @Requirement
    private Logger logger;

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        boolean enabled = booleanProperty(session, "scenariomesh.enabled", true);
        boolean explicitlySkipped = booleanProperty(session, "skipTests", false)
                || booleanProperty(session, "maven.test.skip", false);
        if (!enabled || explicitlySkipped) {
            return;
        }

        for (MavenProject project : session.getProjects()) {
            if ("pom".equals(project.getPackaging())) {
                continue;
            }

            ProjectCompatibilityDetector.CompatibilityDecision decision = compatibilityDetector.evaluate(session, project);
            if (!decision.compatible()) {
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + decision.reason());
                continue;
            }

            injectScenarioMesh(project);
            project.getProperties().setProperty("skipTests", "true");
            info("ScenarioMesh: takeover enabled for " + project.getArtifactId()
                    + " (" + String.join(", ", decision.frameworks()) + ")");
        }
    }

    private void injectScenarioMesh(MavenProject project) {
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + PLUGIN_ARTIFACT_ID);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setGroupId(GROUP_ID);
            plugin.setArtifactId(PLUGIN_ARTIFACT_ID);
            plugin.setVersion(VERSION);
            project.getBuild().addPlugin(plugin);
        }
        boolean alreadyPresent = plugin.getExecutions().stream()
                .anyMatch(execution -> "scenariomesh-test".equals(execution.getId()));
        if (alreadyPresent) {
            return;
        }
        PluginExecution execution = new PluginExecution();
        execution.setId("scenariomesh-test");
        execution.setPhase("test");
        execution.addGoal("run");
        plugin.addExecution(execution);
    }

    private boolean booleanProperty(MavenSession session, String key, boolean fallback) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) {
            value = session.getSystemProperties().getProperty(key);
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
    }

    private void info(String message) {
        if (logger != null) {
            logger.info(message);
        } else {
            System.out.println("[INFO] " + message);
        }
    }
}
