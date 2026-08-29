package io.scenariomesh.maven;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ConfigResolver.ConfigResolution;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.coordinator.PreparedRemoteWorkers;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.coordinator.RunRequest;
import io.scenariomesh.coordinator.ScenarioMeshRunner;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.reporting.LatestReportCleaner;
import io.scenariomesh.reporting.ReportExporters;
import io.scenariomesh.reporting.ReportWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
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
    @Component
    private ToolchainManager toolchainManager;

    @Parameter private String invocationId;
    @Parameter(defaultValue = "false") private boolean deferFailureUntilVerify;
    @Parameter(defaultValue = "surefire") private String takeoverExecutor;
    @Parameter(defaultValue = "false") private boolean testFailureIgnore;
    @Parameter private List<String> includeClassNameRegexes;
    @Parameter private List<String> excludeClassNameRegexes;
    @Parameter private List<String> executorJvmArgs;
    @Parameter private Map<String, String> executorSystemProperties;
    @Parameter(defaultValue = "true") private boolean enableAssertions;
    @Parameter private List<String> executorEnvironmentEntries;
    @Parameter private List<String> excludedEnvironmentVariables;
    @Parameter private String executorWorkingDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if ("true".equalsIgnoreCase(session.getUserProperties().getProperty("skipTests"))
                || "true".equalsIgnoreCase(session.getUserProperties().getProperty("maven.test.skip"))) {
            RemotePreflightState.clear(getPluginContext());
            getLog().info("ScenarioMesh: tests were explicitly skipped by the Maven command.");
            return;
        }

        PreflightState.State preflight = PreflightState.read(project);
        if (preflight == PreflightState.State.PASS_THROUGH) {
            RemotePreflightState.clear(getPluginContext());
            getLog().info("ScenarioMesh: runtime preflight selected native Maven pass-through; ScenarioMesh run is inactive. "
                    + PreflightState.reason(project));
            return;
        }
        if (preflight == PreflightState.State.OWNED) {
            getLog().info("ScenarioMesh: takeover enabled after runtime ownership preflight. " + PreflightState.reason(project));
        } else {
            getLog().debug("ScenarioMesh preflight state is absent; proceeding as an explicit/direct ScenarioMesh run.");
        }

        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        PreparedRemoteWorkers preparedRemoteWorkers = null;
        try {
            Map<String, String> userProperties = EffectiveMavenProperties.user(session);
            Map<String, String> configProperties = EffectiveMavenProperties.configuration(project, session);
            Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
            ConfigResolution resolution = new ConfigResolver().resolveDetailed(
                    projectDirectory, buildDirectory, configProperties, System.getenv());
            ScenarioMeshConfig config = resolution.config();
            if (!config.enabled()) {
                RemotePreflightState.clear(getPluginContext());
                getLog().info("ScenarioMesh disabled; normal Maven test execution remains active.");
                return;
            }

            DiscoverySelection selection = new DiscoverySelection(
                    includeClassNameRegexes == null ? List.of() : includeClassNameRegexes,
                    excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            Path testJava = new TestJvmResolver().resolve(project, session, toolchainManager, takeoverExecutor, null);
            if (config.showConfiguration()) logConfiguration(config, resolution, testJava);

            RuntimeClasspathResolver.RuntimeClasspaths classpaths =
                    new RuntimeClasspathResolver().resolveSplit(project, pluginArtifacts);
            Path workingDirectory = executorWorkingDirectory == null || executorWorkingDirectory.isBlank()
                    ? projectDirectory : Path.of(executorWorkingDirectory).toAbsolutePath().normalize();
            RunRequest request = new RunRequest(
                    projectDirectory,
                    classpaths.targetClasspath(),
                    classpaths.controlClasspath(),
                    new TestRootResolver().resolve(project),
                    userProperties,
                    config,
                    selection,
                    executorJvmArgs == null ? List.of() : executorJvmArgs,
                    executorSystemProperties == null ? Map.of() : executorSystemProperties,
                    testJava,
                    enableAssertions,
                    decodeEnvironmentEntries(executorEnvironmentEntries),
                    excludedEnvironmentVariables == null ? Set.of() : new LinkedHashSet<>(excludedEnvironmentVariables),
                    workingDirectory);

            if (config.distributed().remote()) {
                preparedRemoteWorkers = RemotePreflightState.take(getPluginContext());
                if (preflight == PreflightState.State.OWNED && preparedRemoteWorkers == null) {
                    throw new IllegalStateException("Remote Maven takeover was marked owned without the exact preflight-authenticated worker sessions");
                }
            } else {
                RemotePreflightState.clear(getPluginContext());
            }

            new LatestReportCleaner().clear(config.reportingDirectory());
            RunOutcome outcome = new ScenarioMeshRunner().run(request, preparedRemoteWorkers);
            preparedRemoteWorkers = null;
            ReportWriter.ReportPaths reports = new ReportWriter().write(outcome, config.reportingDirectory());
            ReportExporters.export(outcome, config.reportingDirectory(), reports);
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
                DeferredVerificationState.write(buildDirectory, invocationId, effectiveSuccess,
                        reports.latestHtml().toString(),
                        effectiveSuccess ? null : "ScenarioMesh run contained failing or infrastructure results");
                if (!effectiveSuccess) {
                    getLog().warn("ScenarioMesh recorded failures for Maven verify; post-integration-test lifecycle phases will continue.");
                }
                return;
            }
            if (!effectiveSuccess) throw new MojoFailureException("ScenarioMesh run failed. See " + reports.latestHtml());
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
        } finally {
            if (preparedRemoteWorkers != null) preparedRemoteWorkers.close();
            RemotePreflightState.clear(getPluginContext());
        }
    }

    private Map<String, String> decodeEnvironmentEntries(List<String> encodedEntries) {
        if (encodedEntries == null || encodedEntries.isEmpty()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String encoded : encodedEntries) {
            int separator = encoded == null ? -1 : encoded.indexOf(':');
            if (separator <= 0 || separator == encoded.length() - 1) {
                throw new IllegalArgumentException("Invalid internal Maven environment entry encoding");
            }
            String key = new String(decoder.decode(encoded.substring(0, separator)), StandardCharsets.UTF_8);
            String value = new String(decoder.decode(encoded.substring(separator + 1)), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private void logConfiguration(ScenarioMeshConfig config, ConfigResolution resolution, Path testJava) {
        getLog().info("---------------- ScenarioMesh runtime ----------------");
        getLog().info("ScenarioMesh version       : 0.1.0-SNAPSHOT");
        getLog().info("Project                    : " + project.getArtifactId());
        getLog().info("Requested Maven goals      : " + String.join(" ", session.getRequest().getGoals()));
        getLog().info("Maven executor takeover    : " + takeoverExecutor);
        getLog().info("Execution phase ownership  : " + (deferFailureUntilVerify ? "integration-test/verify" : "test"));
        getLog().info("Test JVM                   : " + testJava);
        getLog().info("Adapter intent             : " + config.executionAdapter());
        getLog().info("Adapter mismatch policy    : " + config.adapterMismatchPolicy().externalValue());
        getLog().info("Infrastructure retries     : " + config.infrastructureRetries()
                + (config.infrastructureRetries() > 0 ? " (at-least-once under uncertain worker/transport failure)" : ""));
        getLog().info("Workers                    : " + config.workerCount() + " "
                + (config.distributed().remote() ? "remote JVM(s)" : "isolated local JVM(s)"));
        getLog().info("Worker mode                : " + config.distributed().mode().externalValue());
        getLog().info("Scheduler                  : history-aware LPT with FIFO fallback and lifecycle affinity");
        getLog().info("Live worker console logs   : " + enabled(config.liveConsoleLogs()));
        getLog().info("Per-worker log files       : " + enabled(config.workerLogFiles()));
        getLog().info("Progress output            : " + enabled(config.showProgress()));
        getLog().info("Report directory           : " + config.reportingDirectory());
        resolution.configFile().ifPresentOrElse(
                path -> getLog().info("Config file                : " + path),
                () -> getLog().info("Config file                : none (defaults/overrides)"));
        getLog().info("------------------------------------------------------");
    }

    private String enabled(boolean value) { return value ? "enabled" : "disabled"; }

    private boolean effectiveSuccess(RunOutcome outcome) {
        if (outcome.successful()) return true;
        if (!testFailureIgnore) return false;
        return outcome.results().stream()
                .allMatch(result -> result.buildSuccessful() || result.status() == ResultStatus.TEST_FAILURE);
    }
}
