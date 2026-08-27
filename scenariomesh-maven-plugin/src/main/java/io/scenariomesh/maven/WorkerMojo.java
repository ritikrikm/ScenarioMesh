package io.scenariomesh.maven;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.config.TlsConfig;
import io.scenariomesh.workerruntime.WorkerMain;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Joins a coordinator from a CI-allocated executor without exposing secrets in child process arguments. */
@Mojo(name = "worker", threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class WorkerMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;
    @Component private ToolchainManager toolchainManager;

    @Parameter(property = "scenariomesh.worker.host", required = true) private String host;
    @Parameter(property = "scenariomesh.worker.port", required = true) private Integer port;
    @Parameter(property = "scenariomesh.distributed.token", required = true) private String token;
    @Parameter(property = "scenariomesh.worker.id") private String workerId;

    @Override public void execute() throws MojoExecutionException {
        try {
            if (host == null || host.isBlank()) throw new IllegalArgumentException("scenariomesh.worker.host is required");
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("scenariomesh.worker.port must be between 1 and 65535");
            }
            if (token == null || token.isBlank()) throw new IllegalArgumentException("scenariomesh.distributed.token is required");

            Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
            Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
            Map<String, String> properties = stringProperties(session.getSystemProperties());
            properties.putAll(stringProperties(session.getUserProperties()));
            ScenarioMeshConfig config = new ConfigResolver().resolve(projectDirectory, buildDirectory, properties, System.getenv());
            Path java = new TestJvmResolver().resolve(project, session, toolchainManager, "surefire", null);
            List<Path> classpath = new RuntimeClasspathResolver().resolve(project, pluginArtifacts);
            String id = effectiveWorkerId();

            List<String> command = new ArrayList<>();
            command.add(java.toString());
            command.addAll(config.workerJvmArgs());
            for (Map.Entry<String, String> property : stringProperties(session.getUserProperties()).entrySet()) {
                if (safeSystemPropertyName(property.getKey())) command.add("-D" + property.getKey() + "=" + property.getValue());
            }
            command.add("-cp");
            command.add(classpath.stream().map(Path::toString).reduce((a, b) -> a + File.pathSeparator + b).orElse(""));
            command.add(WorkerMain.class.getName());
            command.add("--host"); command.add(host.trim());
            command.add("--port"); command.add(Integer.toString(port));
            command.add("--worker-id"); command.add(id);

            ProcessBuilder builder = new ProcessBuilder(command).directory(projectDirectory.toFile()).inheritIO();
            Map<String, String> childEnvironment = builder.environment();
            childEnvironment.put("SCENARIOMESH_REMOTE_TOKEN", token.trim());
            TlsConfig tls = config.distributed().tls();
            childEnvironment.put("SCENARIOMESH_REMOTE_TLS_ENABLED", Boolean.toString(tls.enabled()));
            if (tls.enabled()) {
                childEnvironment.put("SCENARIOMESH_REMOTE_TLS_KEY_STORE", tls.keyStore().toString());
                childEnvironment.put("SCENARIOMESH_REMOTE_TLS_KEY_STORE_PASSWORD", tls.keyStorePassword());
                childEnvironment.put("SCENARIOMESH_REMOTE_TLS_TRUST_STORE", tls.trustStore().toString());
                childEnvironment.put("SCENARIOMESH_REMOTE_TLS_TRUST_STORE_PASSWORD", tls.trustStorePassword());
            }

            getLog().info("ScenarioMesh remote worker " + id + " connecting to " + host.trim() + ":" + port
                    + " transport=" + (tls.enabled() ? "tls" : "loopback-plain")
                    + " using test JVM " + java + ". Authentication and TLS secrets are not passed as process arguments.");
            Process process = builder.start();
            int exit = process.waitFor();
            if (exit != 0) throw new MojoExecutionException("ScenarioMesh remote worker exited with code " + exit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("ScenarioMesh remote worker was interrupted", exception);
        } catch (MojoExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("ScenarioMesh remote worker failed: " + exception.getMessage(), exception);
        }
    }

    private String effectiveWorkerId() {
        if (workerId != null && !workerId.isBlank()) return workerId.trim();
        String node = System.getenv("JENKINS_NODE_NAME");
        if (node == null || node.isBlank()) node = "remote";
        String executor = System.getenv("EXECUTOR_NUMBER");
        if (executor == null || executor.isBlank()) executor = "0";
        return sanitize(node) + "-" + sanitize(executor) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String sanitize(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private boolean safeSystemPropertyName(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase();
        return !lower.contains("password") && !lower.contains("secret") && !lower.contains("token")
                && !lower.contains("credential") && !lower.contains("key");
    }

    private Map<String, String> stringProperties(Properties properties) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (properties != null) properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }
}
