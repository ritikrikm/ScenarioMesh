package io.scenariomesh.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.workerruntime.JsonCodec;
import io.scenariomesh.workerruntime.PreflightProbeMain;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Proves runtime ownership in the exact JVM Maven selected for target tests. */
@Mojo(name = "preflight", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class PreflightMojo extends AbstractMojo {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;
    @Component
    private ToolchainManager toolchainManager;
    @Parameter(defaultValue = "surefire")
    private String takeoverExecutor;
    @Parameter(defaultValue = "false")
    private boolean knownModelFramework;
    @Parameter
    private List<String> includeClassNameRegexes;
    @Parameter
    private List<String> excludeClassNameRegexes;

    @Override
    public void execute() {
        if (explicitlySkipped()) {
            PreflightState.passThrough(project, "tests were explicitly skipped by Maven");
            return;
        }

        try {
            if (remoteDistributedOwnershipRequested()) {
                passThrough("remote distributed execution is configured, but transparent takeover cannot yet prove the registered remote worker set before native Maven suppression; use native Maven or an explicit/direct ScenarioMesh remote run until two-phase distributed ownership is available");
                return;
            }

            if (new ModulePathCompatibility().nativeExecutorUsesModulePath(project, session, normalizedExecutor())) {
                passThrough("JPMS module-path execution is active; ScenarioMesh currently launches target tests on the classpath and will not change native module semantics");
                return;
            }

            List<Path> runtimeClasspath = new RuntimeClasspathResolver().resolve(project, pluginArtifacts);
            List<Path> testRoots = new TestRootResolver().resolve(project);
            Map<String, String> properties = effectiveProperties();
            List<String> includes = includeClassNameRegexes == null ? List.of() : List.copyOf(includeClassNameRegexes);
            List<String> excludes = excludeClassNameRegexes == null ? List.of() : List.copyOf(excludeClassNameRegexes);
            Path javaExecutable = new TestJvmResolver().resolve(project, session, toolchainManager, takeoverExecutor, null);

            PreflightProbeMain.ProbeResult probe = probe(
                    javaExecutable, runtimeClasspath, testRoots, properties, includes, excludes);

            if ("DETECTED_NOT_OWNABLE".equals(probe.ownership())) {
                passThrough("runtime backend is detected but not safely ownable: " + probe.summary());
                return;
            }
            if ("NOT_DETECTED".equals(probe.ownership()) && !knownModelFramework) {
                passThrough("no executable runtime backend was detected and no known legacy framework signal exists: "
                        + probe.summary());
                return;
            }

            PreflightState.owned(project, probe.summary() + "; testJvm=" + javaExecutable);
            suppressNativeExecutor();
            getLog().info("ScenarioMesh preflight: ownership proven in Maven-selected test JVM " + javaExecutable
                    + "; native " + normalizedExecutor() + " execution will be suppressed. Backend inventory: "
                    + probe.summary());
        } catch (Exception | LinkageError exception) {
            passThrough("preflight could not prove complete runtime ownership: " + message(exception));
        }
    }

    private boolean remoteDistributedOwnershipRequested() throws Exception {
        Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        return new ConfigResolver().resolveDetailed(
                projectDirectory, buildDirectory, effectiveProperties(), System.getenv())
                .config().distributed().remote();
    }

    private PreflightProbeMain.ProbeResult probe(Path javaExecutable,
                                                  List<Path> runtimeClasspath,
                                                  List<Path> testRoots,
                                                  Map<String, String> properties,
                                                  List<String> includes,
                                                  List<String> excludes) throws Exception {
        Path directory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize()
                .resolve("scenariomesh-preflight");
        Files.createDirectories(directory);
        Path output = directory.resolve("probe.json");
        Path log = directory.resolve("probe.log");

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-ea");
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> command.add("-D" + entry.getKey() + "=" + entry.getValue()));
        command.add("-cp");
        command.add(runtimeClasspath.stream().map(Path::toString)
                .reduce((left, right) -> left + File.pathSeparator + right).orElse(""));
        command.add(PreflightProbeMain.class.getName());
        command.add("--output");
        command.add(output.toString());
        for (Path root : testRoots) {
            command.add("--test-root");
            command.add(root.toString());
        }
        for (String include : includes) {
            command.add("--include-class-regex");
            command.add(include);
        }
        for (String exclude : excludes) {
            command.add("--exclude-class-regex");
            command.add(exclude);
        }

        Process process = new ProcessBuilder(command)
                .directory(project.getBasedir())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("selected-JVM ownership probe exceeded " + PROBE_TIMEOUT + "; see " + log);
        }
        if (process.exitValue() != 0) {
            String detail = Files.exists(log) ? Files.readString(log) : "no probe log";
            throw new IllegalStateException("selected-JVM ownership probe exited " + process.exitValue()
                    + "; see " + log + System.lineSeparator() + detail);
        }
        ObjectMapper mapper = JsonCodec.create();
        return mapper.readValue(output.toFile(), PreflightProbeMain.ProbeResult.class);
    }

    private void passThrough(String reason) {
        PreflightState.passThrough(project, reason);
        getLog().info("ScenarioMesh preflight: native Maven pass-through - " + reason);
    }

    private void suppressNativeExecutor() {
        if ("failsafe".equals(normalizedExecutor())) project.getProperties().setProperty("skipITs", "true");
        else project.getProperties().setProperty("skipTests", "true");
    }

    private String normalizedExecutor() {
        return takeoverExecutor == null ? "surefire" : takeoverExecutor.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean explicitlySkipped() {
        return booleanProperty("skipTests") || booleanProperty("maven.test.skip")
                || ("failsafe".equals(normalizedExecutor()) && booleanProperty("skipITs"));
    }

    private boolean booleanProperty(String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) value = session.getSystemProperties().getProperty(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private Map<String, String> effectiveProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        project.getProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        session.getSystemProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        session.getUserProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }
}
