package io.scenariomesh.maven.extension;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ConfigResolver.ConfigResolution;
import io.scenariomesh.config.ScenarioMeshConfig;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "scenariomesh")
public final class ScenarioMeshLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private static final String GROUP_ID = "io.scenariomesh";
    private static final String PLUGIN_ARTIFACT_ID = "scenariomesh-maven-plugin";
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final String RUN_EXECUTION_ID = "scenariomesh-run";
    private static final String VERIFY_EXECUTION_ID = "scenariomesh-verify";

    private final ProjectCompatibilityDetector compatibilityDetector = new ProjectCompatibilityDetector();
    private final DownstreamReportCompatibility downstreamReportCompatibility = new DownstreamReportCompatibility();
    private final ConfigResolver configResolver = new ConfigResolver();
    @Requirement private Logger logger;

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        if (booleanProperty(session, "skipTests") || booleanProperty(session, "maven.test.skip")) return;
        Map<String, String> configProperties = stringProperties(session.getSystemProperties());
        configProperties.putAll(stringProperties(session.getUserProperties()));

        for (MavenProject project : session.getProjects()) {
            if ("pom".equals(project.getPackaging())) continue;
            ScenarioMeshConfig config;
            ConfigResolution resolution;
            try {
                Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
                Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
                resolution = configResolver.resolveDetailed(projectDirectory, buildDirectory, configProperties, System.getenv());
                config = resolution.config();
            } catch (IllegalArgumentException exception) {
                throw new MavenExecutionException(
                        "ScenarioMesh configuration error for project '" + project.getArtifactId() + "': " + exception.getMessage(),
                        exception);
            }

            if (!config.enabled()) {
                info("ScenarioMesh: disabled for " + project.getArtifactId() + "; normal Maven execution remains active.");
                continue;
            }

            ProjectCompatibilityDetector.CompatibilityDecision decision = compatibilityDetector.evaluate(session, project);
            if (!decision.compatible()) {
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + decision.reason());
                continue;
            }

            String invocationId = UUID.randomUUID().toString();
            DownstreamReportCompatibility.Analysis reportAnalysis = downstreamReportCompatibility.prepare(project, invocationId);
            if (reportAnalysis.present() && !reportAnalysis.supported()) {
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + reportAnalysis.reason());
                continue;
            }

            injectScenarioMesh(project, decision, invocationId, reportAnalysis.runtimeProperties());
            suppressOwnedExecutor(project, decision.executorKind());
            String configText = resolution.configFile().map(path -> ", config=" + path).orElse("");
            info("ScenarioMesh: takeover enabled for " + project.getArtifactId()
                    + " (executor=" + decision.executorKind().name().toLowerCase()
                    + ", phase=" + decision.takeoverPhase()
                    + ", signals=" + String.join(", ", decision.frameworks())
                    + ", adapterIntent=" + config.executionAdapter() + configText + ")");
            if (reportAnalysis.present()) {
                info("ScenarioMesh: preserving Cluecumber JSON input for this invocation at "
                        + reportAnalysis.sourceJsonDirectory());
            }
        }
    }

    private void suppressOwnedExecutor(MavenProject project, ProjectCompatibilityDetector.ExecutorKind executorKind) {
        if (executorKind == ProjectCompatibilityDetector.ExecutorKind.FAILSAFE) project.getProperties().setProperty("skipITs", "true");
        else project.getProperties().setProperty("skipTests", "true");
    }

    private void injectScenarioMesh(
            MavenProject project,
            ProjectCompatibilityDetector.CompatibilityDecision decision,
            String invocationId,
            Map<String, String> downstreamRuntimeProperties) {
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + PLUGIN_ARTIFACT_ID);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setGroupId(GROUP_ID);
            plugin.setArtifactId(PLUGIN_ARTIFACT_ID);
            plugin.setVersion(VERSION);
            project.getBuild().addPlugin(plugin);
        }

        // Reconcile rather than only "add if absent". A target POM, inherited plugin,
        // or repeated model mutation may already contain these IDs with stale phase/config.
        PluginExecution run = execution(plugin, RUN_EXECUTION_ID);
        if (run == null) {
            run = new PluginExecution();
            run.setId(RUN_EXECUTION_ID);
            plugin.addExecution(run);
        }
        run.setPhase(decision.takeoverPhase());
        run.getGoals().clear();
        run.addGoal("run");
        run.setConfiguration(runConfiguration(decision, invocationId, downstreamRuntimeProperties));

        if (decision.deferFailureUntilVerify()) {
            PluginExecution verify = execution(plugin, VERIFY_EXECUTION_ID);
            if (verify == null) {
                verify = new PluginExecution();
                verify.setId(VERIFY_EXECUTION_ID);
                plugin.addExecution(verify);
            }
            verify.setPhase("verify");
            verify.getGoals().clear();
            verify.addGoal("verify");
            Xpp3Dom verifyConfig = new Xpp3Dom("configuration");
            addValue(verifyConfig, "invocationId", invocationId);
            verify.setConfiguration(verifyConfig);
        } else {
            plugin.getExecutions().removeIf(execution -> VERIFY_EXECUTION_ID.equals(execution.getId()));
        }
    }

    private PluginExecution execution(Plugin plugin, String id) {
        return plugin.getExecutions().stream()
                .filter(execution -> id.equals(execution.getId()))
                .findFirst()
                .orElse(null);
    }

    private Xpp3Dom runConfiguration(
            ProjectCompatibilityDetector.CompatibilityDecision decision,
            String invocationId,
            Map<String, String> downstreamRuntimeProperties) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        addValue(root, "invocationId", invocationId);
        addValue(root, "deferFailureUntilVerify", Boolean.toString(decision.deferFailureUntilVerify()));
        addValue(root, "takeoverExecutor", decision.executorKind().name().toLowerCase());
        addValue(root, "testFailureIgnore", Boolean.toString(decision.testFailureIgnore()));
        addList(root, "includeClassNameRegexes", "include", decision.includeClassNameRegexes());
        addList(root, "excludeClassNameRegexes", "exclude", decision.excludeClassNameRegexes());
        addList(root, "executorJvmArgs", "arg", decision.executorJvmArgs());
        Map<String, String> systemProperties = new LinkedHashMap<>(decision.executorSystemProperties());
        systemProperties.putAll(downstreamRuntimeProperties);
        addMap(root, "executorSystemProperties", systemProperties);
        return root;
    }

    private void addValue(Xpp3Dom root, String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        root.addChild(node);
    }

    private void addList(Xpp3Dom root, String name, String itemName, List<String> values) {
        if (values.isEmpty()) return;
        Xpp3Dom list = new Xpp3Dom(name);
        for (String value : values) {
            Xpp3Dom item = new Xpp3Dom(itemName);
            item.setValue(value);
            list.addChild(item);
        }
        root.addChild(list);
    }

    private void addMap(Xpp3Dom root, String name, Map<String, String> values) {
        if (values.isEmpty()) return;
        Xpp3Dom map = new Xpp3Dom(name);
        values.forEach((key, value) -> {
            Xpp3Dom item = new Xpp3Dom(key);
            item.setValue(value);
            map.addChild(item);
        });
        root.addChild(map);
    }

    private boolean booleanProperty(MavenSession session, String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) value = session.getSystemProperties().getProperty(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private Map<String, String> stringProperties(java.util.Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        if (properties != null) properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private void info(String message) {
        if (logger != null) logger.info(message);
        else System.out.println("[INFO] " + message);
    }
}
