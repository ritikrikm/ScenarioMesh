package io.scenariomesh.maven;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.workerruntime.ExecutionBackendInventory;
import io.scenariomesh.workerruntime.FrameworkOwnershipGuard;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves runtime framework ownership after test compilation and before native
 * Surefire/Failsafe execution is suppressed.
 *
 * <p>This is deliberately fail-closed for ScenarioMesh ownership and fail-open
 * for Maven: any uncertainty leaves the native Maven executor active.</p>
 */
@Mojo(name = "preflight", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class PreflightMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;
    @Parameter(defaultValue = "surefire")
    private String takeoverExecutor;
    @Parameter(defaultValue = "false")
    private boolean knownModelFramework;

    @Override
    public void execute() {
        if (explicitlySkipped()) {
            PreflightState.passThrough(project, "tests were explicitly skipped by Maven");
            return;
        }

        try {
            List<Path> runtimeClasspath = new RuntimeClasspathResolver().resolve(project, pluginArtifacts);
            List<Path> testRoots = new TestRootResolver().resolve(project);
            Map<String, String> properties = effectiveProperties();

            URL[] urls = runtimeClasspath.stream().map(this::toUrl).toArray(URL[]::new);
            try (URLClassLoader targetLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
                DiscoverySelection allSelected = new DiscoverySelection(List.of(), List.of());
                AdapterContext context = new AdapterContext(targetLoader, testRoots, properties, allSelected);

                new FrameworkOwnershipGuard().verifyNoUnsupportedExecutableFamilies(context);
                ExecutionBackendInventory.Inventory inventory = ExecutionBackendInventory.inspect(
                        targetLoader, testRoots, List.of(), List.of());

                if (inventory.ownership() == ExecutionBackendInventory.Ownership.DETECTED_NOT_OWNABLE) {
                    passThrough("runtime backend is detected but not safely ownable: " + inventory.summary());
                    return;
                }
                if (inventory.ownership() == ExecutionBackendInventory.Ownership.NOT_DETECTED && !knownModelFramework) {
                    passThrough("no executable runtime backend was detected and no known legacy framework signal exists: "
                            + inventory.summary());
                    return;
                }

                // NOT_DETECTED remains valid only for known legacy paths such as Cucumber JUnit4
                // and TestNG, which are not required to expose a JUnit Platform TestEngine.
                PreflightState.owned(project, inventory.summary());
                suppressNativeExecutor();
                getLog().info("ScenarioMesh preflight: ownership proven; native " + normalizedExecutor()
                        + " execution will be suppressed. Backend inventory: " + inventory.summary());
            }
        } catch (Exception | LinkageError exception) {
            passThrough("preflight could not prove complete runtime ownership: " + message(exception));
        }
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
        session.getSystemProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        session.getUserProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid runtime classpath element: " + path, exception);
        }
    }

    private String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }
}
