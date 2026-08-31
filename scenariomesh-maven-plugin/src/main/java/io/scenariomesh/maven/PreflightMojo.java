package io.scenariomesh.maven;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.MavenOwnershipDiagnostic;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.workerruntime.PreflightProbeMain;
import io.scenariomesh.workerruntime.TargetClasspathDescriptor;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Proves runtime ownership in the exact JVM and process context Maven selected for target tests.
 * For multiple native Maven test executions every injected run plan is proven independently.
 */
@Mojo(name = "preflight", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class PreflightMojo extends AbstractMojo {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> MAVEN_RERUN_ADAPTERS = Set.of("junit-platform", "testng");
    private static final String SCENARIOMESH_PLUGIN = "io.scenariomesh:scenariomesh-maven-plugin";
    private static final String RUN_EXECUTION_ID = "scenariomesh-run";

    @Parameter(defaultValue = "${project}", readonly = true, required = true) private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true) private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true) private List<Artifact> pluginArtifacts;
    @Component private ToolchainManager toolchainManager;
    @Parameter(defaultValue = "surefire") private String takeoverExecutor;
    @Parameter(defaultValue = "false") private boolean knownModelFramework;
    @Parameter private List<String> takeoverExecutionIds;
    @Parameter private List<String> includeClassNameRegexes;
    @Parameter private List<String> excludeClassNameRegexes;
    @Parameter private List<String> executorJvmArgs;
    @Parameter private Map<String, String> executorSystemProperties;
    @Parameter(defaultValue = "true") private boolean enableAssertions;
    @Parameter private List<String> executorEnvironmentEntries;
    @Parameter private List<String> excludedEnvironmentVariables;
    @Parameter private String executorWorkingDirectory;
    @Parameter private List<String> additionalClasspathElements;
    @Parameter private List<String> classpathDependencyExcludes;
    @Parameter private String classpathDependencyScopeExclude;
    @Parameter private List<String> dependencyTestScanPatterns;

    @Override
    public void execute() {
        RemotePreflightState.clear(getPluginContext());
        if (explicitlySkipped()) {
            passThrough("tests were explicitly skipped by Maven");
            return;
        }

        try {
            Map<String, String> configProperties = EffectiveMavenProperties.configuration(project, session);
            ScenarioMeshConfig config = resolveConfig(configProperties);
            Path javaExecutable = new TestJvmResolver().resolve(project, session, toolchainManager, takeoverExecutor, null);
            List<ProbePlan> plans = probePlans();
            if (plans.isEmpty()) {
                passThrough("no ScenarioMesh execution plan is available for runtime ownership proof");
                return;
            }

            if (config.distributed().remote()) {
                passThrough("transparent Maven takeover cannot prove native Surefire/Failsafe fork-process equivalence across remote agents (inherited environment, working directory, and host process context differ); native Maven execution is retained. Direct ScenarioMesh remote execution remains available outside transparent takeover.");
                return;
            }

            List<String> summaries = new ArrayList<>();
            for (ProbePlan plan : plans) {
                PreflightProbeMain.ProbeResult probe = provePlan(plan, javaExecutable);
                summaries.add(plan.executionId() + "={" + probe.summary() + "}");
            }

            String inventory = String.join(", ", summaries);
            String reason = "runtime ownership proven independently for " + plans.size()
                    + " Maven execution plan(s) in Maven-selected test JVM; " + inventory;
            PreflightState.owned(project, inventory + "; testJvm=" + javaExecutable);
            suppressNativeExecutor();
            ownership(MavenOwnershipDiagnostic.Owner.SCENARIOMESH, reason);
            getLog().info("ScenarioMesh preflight: ownership proven for all " + plans.size()
                    + " Maven execution plan(s) in selected test JVM " + javaExecutable
                    + "; native " + normalizedExecutor() + " execution will be suppressed. " + inventory);
        } catch (Exception | LinkageError exception) {
            passThrough("preflight could not prove complete runtime ownership: " + message(exception));
        }
    }

    private PreflightProbeMain.ProbeResult provePlan(ProbePlan plan, Path javaExecutable) throws Exception {
        RuntimeClasspathResolver.RuntimeClasspaths classpaths = new RuntimeClasspathResolver().resolveSplit(
                project, pluginArtifacts, plan.additionalClasspathElements(), plan.classpathDependencyExcludes(),
                plan.classpathDependencyScopeExclude());
        List<Path> testRoots = new TestRootResolver().resolve(project, plan.dependencyTestScanPatterns());

        Map<String, String> testSystemProperties = new LinkedHashMap<>(plan.executorSystemProperties());
        String executorArgLine = testSystemProperties.remove(RuntimePropertyNames.MAVEN_EXECUTOR_ARG_LINE);
        boolean promoteUserProperties = removeInternalBoolean(
                testSystemProperties, RuntimePropertyNames.MAVEN_PROMOTE_USER_PROPERTIES, true);
        removeInternalControlProperties(testSystemProperties);
        if (promoteUserProperties) testSystemProperties.putAll(EffectiveMavenProperties.user(session));
        List<String> effectiveJvmArgs = new ArrayList<>(MavenArgLineSupport.merge(
                plan.executorJvmArgs(), executorArgLine, project, session));

        ModulePathCompatibility.LaunchPlan moduleLaunch = new ModulePathCompatibility().launchPlan(
                project, session, normalizedExecutor(), classpaths.targetClasspath());
        if (moduleLaunch.modulePath()) {
            effectiveJvmArgs.addAll(moduleLaunch.jvmArgs());
            testSystemProperties.put(ModulePathCompatibility.TARGET_MODULE_PATH_PROPERTY, "true");
        }

        int modelReruns = removeInternalNonNegativeInt(
                testSystemProperties, RuntimePropertyNames.MAVEN_RERUN_FAILING_TESTS_COUNT);
        removeInternalNonNegativeInt(testSystemProperties, RuntimePropertyNames.MAVEN_FAIL_ON_FLAKE_COUNT);
        int effectiveReruns = commandLineNonNegativeInt(executorPrefix() + "rerunFailingTestsCount", modelReruns);
        int skipAfterFailureCount = commandLineNonNegativeInt(executorPrefix() + "skipAfterFailureCount", 0);
        if (skipAfterFailureCount > 0) {
            throw new IllegalStateException(executorPrefix() + "skipAfterFailureCount=" + skipAfterFailureCount
                    + " requires an exact global stop-after-failure barrier; ScenarioMesh will not approximate this interaction with retries");
        }

        Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path workingDirectory = plan.executorWorkingDirectory() == null || plan.executorWorkingDirectory().isBlank()
                ? projectDirectory : Path.of(plan.executorWorkingDirectory()).toAbsolutePath().normalize();
        Map<String, String> environment = decodeEnvironmentEntries(plan.executorEnvironmentEntries());
        Set<String> excludedEnvironment = Set.copyOf(new LinkedHashSet<>(plan.excludedEnvironmentVariables()));

        PreflightProbeMain.ProbeResult probe = probe(plan.executionId(), javaExecutable,
                classpaths.controlClasspath(), classpaths.targetClasspath(), testRoots, testSystemProperties,
                List.copyOf(effectiveJvmArgs), plan.includes(), plan.excludes(), plan.enableAssertions(), environment,
                excludedEnvironment, workingDirectory);

        if ("DETECTED_NOT_OWNABLE".equals(probe.ownership())) {
            throw new IllegalStateException("execution '" + plan.executionId()
                    + "' runtime backend is detected but not safely ownable: " + probe.summary());
        }
        if ("NOT_DETECTED".equals(probe.ownership())) {
            throw new IllegalStateException("execution '" + plan.executionId()
                    + "' has no executable runtime backend; ScenarioMesh will not suppress native "
                    + normalizedExecutor() + " without executable-leaf proof: " + probe.summary());
        }
        if (effectiveReruns > 0 && !rerunProviderSupported(probe)) {
            throw new IllegalStateException("execution '" + plan.executionId() + "' uses rerunFailingTestsCount="
                    + effectiveReruns + " but runtime retry ownership is not proven for adapters="
                    + probe.requiredAdapterIds() + ", engines=" + probe.requiredEngineIds());
        }
        return probe;
    }

    private List<ProbePlan> probePlans() {
        Plugin plugin = project.getPlugin(SCENARIOMESH_PLUGIN);
        List<PluginExecution> runs = plugin == null || plugin.getExecutions() == null ? List.of()
                : plugin.getExecutions().stream()
                .filter(execution -> execution.getId() != null
                        && (RUN_EXECUTION_ID.equals(execution.getId())
                        || execution.getId().startsWith(RUN_EXECUTION_ID + "-")))
                .toList();

        if (!runs.isEmpty()) {
            List<String> ids = takeoverExecutionIds == null ? List.of() : List.copyOf(takeoverExecutionIds);
            if (!ids.isEmpty() && ids.size() != runs.size()) {
                throw new IllegalStateException("preflight/run execution-plan count mismatch: expected "
                        + ids.size() + " but found " + runs.size());
            }
            List<ProbePlan> plans = new ArrayList<>();
            for (int index = 0; index < runs.size(); index++) {
                String executionId = ids.isEmpty() ? runs.get(index).getId() : ids.get(index);
                Xpp3Dom root = dom(runs.get(index).getConfiguration());
                plans.add(new ProbePlan(
                        executionId,
                        list(root, "includeClassNameRegexes", "include"),
                        list(root, "excludeClassNameRegexes", "exclude"),
                        list(root, "executorJvmArgs", "arg"),
                        map(root, "executorSystemProperties"),
                        booleanValue(root, "enableAssertions", true),
                        list(root, "executorEnvironmentEntries", "entry"),
                        list(root, "excludedEnvironmentVariables", "name"),
                        value(root, "executorWorkingDirectory"),
                        list(root, "additionalClasspathElements", "element"),
                        list(root, "classpathDependencyExcludes", "exclude"),
                        value(root, "classpathDependencyScopeExclude"),
                        list(root, "dependencyTestScanPatterns", "pattern")));
            }
            return List.copyOf(plans);
        }

        String executionId = takeoverExecutionIds == null || takeoverExecutionIds.isEmpty()
                ? "default" : takeoverExecutionIds.get(0);
        return List.of(new ProbePlan(
                executionId,
                copy(includeClassNameRegexes), copy(excludeClassNameRegexes), copy(executorJvmArgs),
                executorSystemProperties == null ? Map.of() : Map.copyOf(executorSystemProperties),
                enableAssertions, copy(executorEnvironmentEntries), copy(excludedEnvironmentVariables),
                executorWorkingDirectory, copy(additionalClasspathElements), copy(classpathDependencyExcludes),
                classpathDependencyScopeExclude, copy(dependencyTestScanPatterns)));
    }

    private Xpp3Dom dom(Object value) {
        if (value instanceof Xpp3Dom dom) return dom;
        throw new IllegalStateException("ScenarioMesh run execution has no readable configuration");
    }

    private List<String> list(Xpp3Dom root, String containerName, String itemName) {
        Xpp3Dom container = root == null ? null : root.getChild(containerName);
        if (container == null) return List.of();
        List<String> values = new ArrayList<>();
        for (Xpp3Dom child : container.getChildren()) {
            if (!itemName.equals(child.getName())) continue;
            String value = child.getValue();
            if (value != null) values.add(value);
        }
        return List.copyOf(values);
    }

    private Map<String, String> map(Xpp3Dom root, String containerName) {
        Xpp3Dom container = root == null ? null : root.getChild(containerName);
        if (container == null) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (Xpp3Dom child : container.getChildren()) {
            if (child.getValue() != null) values.put(child.getName(), child.getValue());
        }
        return Map.copyOf(values);
    }

    private String value(Xpp3Dom root, String name) {
        Xpp3Dom child = root == null ? null : root.getChild(name);
        return child == null ? null : child.getValue();
    }

    private boolean booleanValue(Xpp3Dom root, String name, boolean fallback) {
        String raw = value(root, name);
        if (raw == null || raw.isBlank()) return fallback;
        if ("true".equalsIgnoreCase(raw.trim())) return true;
        if ("false".equalsIgnoreCase(raw.trim())) return false;
        throw new IllegalStateException("ScenarioMesh run execution <" + name + "> must be true or false");
    }

    private List<String> copy(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    private boolean rerunProviderSupported(PreflightProbeMain.ProbeResult probe) {
        if (probe.requiredAdapterIds().isEmpty()) return false;
        if (!MAVEN_RERUN_ADAPTERS.containsAll(probe.requiredAdapterIds())) return false;
        return !probe.requiredEngineIds().contains("junit-vintage");
    }

    private int removeInternalNonNegativeInt(Map<String, String> properties, String key) {
        String raw = properties.remove(key);
        return raw == null ? 0 : parseNonNegativeInt(key, raw);
    }

    private int commandLineNonNegativeInt(String key, int fallback) {
        String raw = session.getUserProperties().getProperty(key);
        if (raw == null) raw = session.getSystemProperties().getProperty(key);
        return raw == null ? fallback : parseNonNegativeInt(key, raw);
    }

    private int parseNonNegativeInt(String key, String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) throw new IllegalArgumentException(key + " must be >= 0 but was " + raw);
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a non-negative integer but was '" + raw + "'", exception);
        }
    }

    private String executorPrefix() {
        return "failsafe".equals(normalizedExecutor()) ? "failsafe." : "surefire.";
    }

    private ScenarioMeshConfig resolveConfig(Map<String, String> properties) throws Exception {
        Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        return new ConfigResolver().resolveDetailed(projectDirectory, buildDirectory, properties, System.getenv()).config();
    }

    private PreflightProbeMain.ProbeResult probe(String executionId,
                                                  Path javaExecutable,
                                                  List<Path> controlClasspath,
                                                  List<Path> targetClasspath,
                                                  List<Path> testRoots,
                                                  Map<String, String> properties,
                                                  List<String> executorJvmArgs,
                                                  List<String> includes,
                                                  List<String> excludes,
                                                  boolean assertionsEnabled,
                                                  Map<String, String> environmentVariables,
                                                  Set<String> excludedEnvironment,
                                                  Path workingDirectory) throws Exception {
        Path directory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize()
                .resolve("scenariomesh-preflight");
        Files.createDirectories(directory);
        String suffix = safe(executionId);
        Path output = directory.resolve("probe-" + suffix + ".properties");
        Path log = directory.resolve("probe-" + suffix + ".log");

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-ea");
        if (!assertionsEnabled) command.add("-da");
        command.addAll(executorJvmArgs == null ? List.of() : executorJvmArgs);
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> command.add("-D" + entry.getKey() + "=" + entry.getValue()));
        command.add("-D" + TargetClasspathDescriptor.SYSTEM_PROPERTY + "="
                + TargetClasspathDescriptor.encodeInline(targetClasspath));
        command.add("-cp");
        command.add(controlClasspath.stream().map(Path::toString)
                .reduce((left, right) -> left + File.pathSeparator + right).orElse(""));
        command.add(PreflightProbeMain.class.getName());
        command.add("--output");
        command.add(output.toString());
        for (Path root : testRoots) { command.add("--test-root"); command.add(root.toString()); }
        for (String include : includes) { command.add("--include-class-regex"); command.add(include); }
        for (String exclude : excludes) { command.add("--exclude-class-regex"); command.add(exclude); }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        excludedEnvironment.forEach(builder.environment()::remove);
        builder.environment().putAll(environmentVariables);
        Process process = builder.start();
        if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("selected-JVM ownership probe for execution '" + executionId
                    + "' exceeded " + PROBE_TIMEOUT + "; see " + log);
        }
        if (process.exitValue() != 0) {
            String detail = Files.exists(log) ? Files.readString(log) : "no probe log";
            throw new IllegalStateException("selected-JVM ownership probe for execution '" + executionId
                    + "' exited " + process.exitValue() + "; see " + log + System.lineSeparator() + detail);
        }
        return PreflightProbeMain.readResult(output);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "default";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean removeInternalBoolean(Map<String, String> properties, String key, boolean defaultValue) {
        String raw = properties.remove(key);
        if (raw == null) return defaultValue;
        if ("true".equalsIgnoreCase(raw.trim())) return true;
        if ("false".equalsIgnoreCase(raw.trim())) return false;
        throw new IllegalArgumentException("Invalid internal Maven compatibility boolean '" + key + "': " + raw);
    }

    private void removeInternalControlProperties(Map<String, String> properties) {
        properties.remove(RuntimePropertyNames.MAVEN_ZERO_TEST_POLICY_ENABLED);
        properties.remove(RuntimePropertyNames.MAVEN_FAIL_IF_NO_TESTS);
        properties.remove(RuntimePropertyNames.MAVEN_FAIL_IF_NO_SPECIFIED_TESTS);
        properties.remove(RuntimePropertyNames.MAVEN_EXPLICIT_TEST_SELECTION);
        properties.remove(RuntimePropertyNames.MAVEN_RUN_ORDER);
        properties.remove(RuntimePropertyNames.MAVEN_RUN_ORDER_RANDOM_SEED);
        properties.remove(RuntimePropertyNames.MAVEN_RUN_ORDER_STATISTICS_FILE);
    }

    private Map<String, String> decodeEnvironmentEntries(List<String> encodedEntries) {
        if (encodedEntries == null || encodedEntries.isEmpty()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String encoded : encodedEntries) {
            int separator = encoded == null ? -1 : encoded.indexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("Invalid internal Maven environment entry encoding");
            String key = new String(decoder.decode(encoded.substring(0, separator)), StandardCharsets.UTF_8);
            String value = new String(decoder.decode(encoded.substring(separator + 1)), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private void passThrough(String reason) {
        RemotePreflightState.clear(getPluginContext());
        PreflightState.passThrough(project, reason);
        ownership(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, reason);
        getLog().info("ScenarioMesh preflight: native Maven pass-through - " + reason);
    }

    private void ownership(MavenOwnershipDiagnostic.Owner owner, String reason) {
        List<String> executions = takeoverExecutionIds == null || takeoverExecutionIds.isEmpty()
                ? List.of("none") : takeoverExecutionIds;
        for (String execution : executions) {
            getLog().info(MavenOwnershipDiagnostic.format(owner, project.getArtifactId(), normalizedExecutor(), execution, reason));
        }
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

    private String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    private record ProbePlan(
            String executionId,
            List<String> includes,
            List<String> excludes,
            List<String> executorJvmArgs,
            Map<String, String> executorSystemProperties,
            boolean enableAssertions,
            List<String> executorEnvironmentEntries,
            List<String> excludedEnvironmentVariables,
            String executorWorkingDirectory,
            List<String> additionalClasspathElements,
            List<String> classpathDependencyExcludes,
            String classpathDependencyScopeExclude,
            List<String> dependencyTestScanPatterns) {
        private ProbePlan {
            includes = List.copyOf(includes == null ? List.of() : includes);
            excludes = List.copyOf(excludes == null ? List.of() : excludes);
            executorJvmArgs = List.copyOf(executorJvmArgs == null ? List.of() : executorJvmArgs);
            executorSystemProperties = Map.copyOf(executorSystemProperties == null ? Map.of() : executorSystemProperties);
            executorEnvironmentEntries = List.copyOf(executorEnvironmentEntries == null ? List.of() : executorEnvironmentEntries);
            excludedEnvironmentVariables = List.copyOf(excludedEnvironmentVariables == null ? List.of() : excludedEnvironmentVariables);
            additionalClasspathElements = List.copyOf(additionalClasspathElements == null ? List.of() : additionalClasspathElements);
            classpathDependencyExcludes = List.copyOf(classpathDependencyExcludes == null ? List.of() : classpathDependencyExcludes);
            dependencyTestScanPatterns = List.copyOf(dependencyTestScanPatterns == null ? List.of() : dependencyTestScanPatterns);
        }
    }
}
