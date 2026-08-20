package io.scenariomesh.maven;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ConfigResolver.ConfigResolution;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.coordinator.RunRequest;
import io.scenariomesh.coordinator.ScenarioMeshRunner;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.reporting.LatestReportCleaner;
import io.scenariomesh.reporting.ReportWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mojo(name = "run", defaultPhase = LifecyclePhase.TEST, threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class RunMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    @Parameter
    private String invocationId;
    @Parameter(defaultValue = "false")
    private boolean deferFailureUntilVerify;
    @Parameter(defaultValue = "surefire")
    private String takeoverExecutor;
    @Parameter(defaultValue = "false")
    private boolean testFailureIgnore;
    @Parameter
    private List<String> includeClassNameRegexes;
    @Parameter
    private List<String> excludeClassNameRegexes;
    @Parameter
    private List<String> executorJvmArgs;
    @Parameter
    private Map<String, String> executorSystemProperties;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if ("true".equalsIgnoreCase(session.getUserProperties().getProperty("skipTests"))
                || "true".equalsIgnoreCase(session.getUserProperties().getProperty("maven.test.skip"))) {
            getLog().info("ScenarioMesh: tests were explicitly skipped by the Maven command.");
            return;
        }

        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        try {
            Map<String, String> userProperties = stringProperties(session.getUserProperties());
            Map<String, String> configProperties = stringProperties(session.getSystemProperties());
            configProperties.putAll(userProperties);
            Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
            ConfigResolution resolution = new ConfigResolver().resolveDetailed(
                    projectDirectory, buildDirectory, configProperties, System.getenv());
            ScenarioMeshConfig config = resolution.config();
            if (!config.enabled()) {
                getLog().info("ScenarioMesh disabled; normal Maven test execution remains active.");
                return;
            }

            DiscoverySelection selection = new DiscoverySelection(
                    includeClassNameRegexes == null ? List.of() : includeClassNameRegexes,
                    excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);

            if (config.showConfiguration()) {
                logConfiguration(config, resolution);
            }

            RunRequest request = new RunRequest(
                    projectDirectory,
                    runtimeClasspath(),
                    new TestRootResolver().resolve(project),
                    userProperties,
                    config,
                    selection,
                    executorJvmArgs == null ? List.of() : executorJvmArgs,
                    executorSystemProperties == null ? Map.of() : executorSystemProperties);

            // Once ScenarioMesh owns this invocation, stale top-level aliases must not
            // survive a new run that crashes before report publication. Historical
            // runs/<runId> artifacts are retained by the reporting layer.
            new LatestReportCleaner().clear(config.reportingDirectory());

            RunOutcome outcome = new ScenarioMeshRunner().run(request);
            ReportWriter.ReportPaths reports = new ReportWriter().write(outcome, config.reportingDirectory());
            long passed = outcome.results().stream().filter(result -> result.passed()).count();
            long skipped = outcome.results().stream().filter(result -> result.skipped()).count();
            long failed = outcome.results().size() - passed - skipped;
            boolean effectiveSuccess = effectiveSuccess(outcome);
            getLog().info("ScenarioMesh selected adapter: " + String.join(", ", outcome.adapters()));
            getLog().info("ScenarioMesh results: discovered=" + outcome.tasks().size()
                    + ", passed=" + passed + ", skipped=" + skipped
                    + ", failed=" + failed + ", duration=" + outcome.duration());
            getLog().info("ScenarioMesh report: " + reports.latestHtml());
            if (testFailureIgnore && !outcome.successful() && effectiveSuccess) {
                getLog().warn("ScenarioMesh observed test failures, but Maven executor testFailureIgnore=true; infrastructure failures are still fatal.");
            }

            if (deferFailureUntilVerify) {
                DeferredVerificationState.write(
                        buildDirectory,
                        invocationId,
                        effectiveSuccess,
                        reports.latestHtml().toString(),
                        effectiveSuccess ? null : "ScenarioMesh run contained failing or infrastructure results");
                if (!effectiveSuccess) {
                    getLog().warn("ScenarioMesh recorded failures for Maven verify; post-integration-test lifecycle phases will continue.");
                }
                return;
            }
            if (!effectiveSuccess) {
                throw new MojoFailureException("ScenarioMesh run failed. See " + reports.latestHtml());
            }
        } catch (MojoFailureException failure) {
            throw failure;
        } catch (Exception exception) {
            if (deferFailureUntilVerify) {
                try {
                    DeferredVerificationState.write(buildDirectory, invocationId, false, null,
                            "ScenarioMesh infrastructure failure: " + exception.getMessage());
                    getLog().error("ScenarioMesh infrastructure failure recorded for Maven verify: " + exception.getMessage());
                    return;
                } catch (Exception stateFailure) {
                    exception.addSuppressed(stateFailure);
                }
            }
            throw new MojoExecutionException("ScenarioMesh infrastructure failure: " + exception.getMessage(), exception);
        }
    }

    private void logConfiguration(ScenarioMeshConfig config, ConfigResolution resolution) {
        getLog().info("---------------- ScenarioMesh runtime ----------------");
        getLog().info("ScenarioMesh version       : 0.1.0-SNAPSHOT");
        getLog().info("Project                    : " + project.getArtifactId());
        getLog().info("Requested Maven goals      : " + String.join(" ", session.getRequest().getGoals()));
        getLog().info("Maven executor takeover    : " + takeoverExecutor);
        getLog().info("Execution phase ownership  : " + (deferFailureUntilVerify ? "integration-test/verify" : "test"));
        getLog().info("Adapter intent             : " + config.executionAdapter());
        getLog().info("Adapter mismatch policy    : " + config.adapterMismatchPolicy().externalValue());
        getLog().info("Infrastructure retries     : " + config.infrastructureRetries()
                + (config.infrastructureRetries() > 0 ? " (at-least-once under uncertain worker/transport failure)" : ""));
        getLog().info("Workers                    : " + config.workerCount() + " isolated JVM(s)");
        getLog().info("Scheduler                  : FIFO dynamic assignment");
        getLog().info("Live worker console logs   : " + enabled(config.liveConsoleLogs()));
        getLog().info("Per-worker log files       : " + enabled(config.workerLogFiles()));
        getLog().info("Progress output            : " + enabled(config.showProgress()));
        getLog().info("Report directory           : " + config.reportingDirectory());
        resolution.configFile().ifPresentOrElse(
                path -> getLog().info("Config file                : " + path),
                () -> getLog().info("Config file                : none (defaults/overrides)"));
        getLog().info("------------------------------------------------------");
    }

    private String enabled(boolean value) {
        return value ? "enabled" : "disabled";
    }

    private boolean effectiveSuccess(RunOutcome outcome) {
        if (outcome.successful()) {
            return true;
        }
        if (!testFailureIgnore) {
            return false;
        }
        return outcome.results().stream()
                .allMatch(result -> result.buildSuccessful() || result.status() == ResultStatus.TEST_FAILURE);
    }

    private Map<String, String> stringProperties(java.util.Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        if (properties != null) {
            properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        }
        return values;
    }

    private List<Path> runtimeClasspath() throws Exception {
        Set<Path> paths = new LinkedHashSet<>();
        for (String element : project.getTestClasspathElements()) {
            paths.add(Path.of(element).toAbsolutePath().normalize());
        }
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                File file = artifact.getFile();
                if (file != null && file.exists()) {
                    paths.add(file.toPath().toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(paths);
    }
}
