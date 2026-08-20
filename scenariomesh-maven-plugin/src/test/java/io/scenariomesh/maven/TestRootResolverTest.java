package io.scenariomesh.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRootResolverTest {
    @TempDir
    Path temp;

    @Test
    void includesProjectTestOutputsButNotMainOutputOrExternalDirectories() throws Exception {
        Path buildDirectory = Files.createDirectories(temp.resolve("target"));
        Path mainOutput = Files.createDirectories(buildDirectory.resolve("classes"));
        Path standardTestOutput = Files.createDirectories(buildDirectory.resolve("test-classes"));
        Path generatedTestOutput = Files.createDirectories(buildDirectory.resolve("generated-test-classes"));
        Path externalDependencyDirectory = Files.createDirectories(temp.resolve("external-dependency"));

        Model model = new Model();
        Build build = new Build();
        build.setDirectory(buildDirectory.toString());
        build.setOutputDirectory(mainOutput.toString());
        build.setTestOutputDirectory(standardTestOutput.toString());
        model.setBuild(build);

        MavenProject project = new MavenProject(model) {
            @Override
            public List<String> getTestClasspathElements() {
                return List.of(
                        standardTestOutput.toString(),
                        generatedTestOutput.toString(),
                        mainOutput.toString(),
                        externalDependencyDirectory.toString(),
                        generatedTestOutput.resolve("..").resolve("generated-test-classes").toString());
            }
        };

        List<Path> roots = new TestRootResolver().resolve(project);

        assertEquals(
                List.of(
                        standardTestOutput.toAbsolutePath().normalize(),
                        generatedTestOutput.toAbsolutePath().normalize()),
                roots);
    }
}
