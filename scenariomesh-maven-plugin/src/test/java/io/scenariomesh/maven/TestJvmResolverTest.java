package io.scenariomesh.maven;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestJvmResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void unresolvedSimplePropertyIsNullLikeMavenPluginParameterEvaluation() {
        assertNull(TestJvmResolver.resolveProperty(
                "commons.surefire.java", new Properties(), new Properties(), new Properties()));
    }

    @Test
    void systemThenUserThenProjectPropertyPrecedenceMatchesMavenPluginEvaluation() {
        Properties project = properties("test.jvm", "project-java");
        Properties system = properties("test.jvm", "system-java");
        Properties user = properties("test.jvm", "user-java");

        assertEquals("system-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
        system.clear();
        assertEquals("user-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
        user.clear();
        assertEquals("project-java", TestJvmResolver.resolveProperty("test.jvm", project, system, user));
    }

    @Test
    void unresolvedSurefireJvmPropertyFallsBackToCurrentMavenJvm() {
        MavenProject project = projectWithConfiguration(jvmConfiguration("${commons.surefire.java}"));

        Path resolved = new TestJvmResolver().resolve(project, session(), null, "surefire", null);

        assertEquals(currentJavaExecutable(), resolved);
    }

    @Test
    void configuredSurefireJvmPropertyIsHonored() throws IOException {
        Path alternateJava = alternateJavaExecutable();
        MavenProject project = projectWithConfiguration(jvmConfiguration("${test.jvm}"));
        project.getProperties().setProperty("test.jvm", alternateJava.toString());

        Path resolved = new TestJvmResolver().resolve(project, session(), null, "surefire", null);

        assertEquals(alternateJava.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void unresolvedJdkToolchainPropertyRemainsFailClosed() {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom toolchain = new Xpp3Dom("jdkToolchain");
        Xpp3Dom version = new Xpp3Dom("version");
        version.setValue("${missing.toolchain.version}");
        toolchain.addChild(version);
        configuration.addChild(toolchain);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new TestJvmResolver().resolve(
                        projectWithConfiguration(configuration), session(), null, "surefire", null));

        assertTrue(failure.getMessage().contains(
                "Unresolved Maven property ${missing.toolchain.version} in test-JVM configuration"));
    }

    private MavenProject projectWithConfiguration(Xpp3Dom configuration) {
        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.5.2");
        surefire.setConfiguration(configuration);

        Build build = new Build();
        build.addPlugin(surefire);

        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("test-jvm-resolver-fixture");
        model.setVersion("1.0");
        model.setBuild(build);
        return new MavenProject(model);
    }

    private Xpp3Dom jvmConfiguration(String value) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom jvm = new Xpp3Dom("jvm");
        jvm.setValue(value);
        configuration.addChild(jvm);
        return configuration;
    }

    private MavenSession session() {
        return new MavenSession(
                null,
                null,
                new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());
    }

    private Path alternateJavaExecutable() throws IOException {
        Path currentJava = currentJavaExecutable();
        Path alternate = tempDir.resolve(currentJava.getFileName());
        try {
            return Files.createSymbolicLink(alternate, currentJava);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            Files.copy(currentJava, alternate, StandardCopyOption.COPY_ATTRIBUTES);
            if (!isWindows()) assertTrue(alternate.toFile().setExecutable(true));
            return alternate;
        }
    }

    private Path currentJavaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toAbsolutePath()
                .normalize();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private Properties properties(String... entries) {
        Properties properties = new Properties();
        for (int index = 0; index < entries.length; index += 2) {
            properties.setProperty(entries[index], entries[index + 1]);
        }
        return properties;
    }
}
