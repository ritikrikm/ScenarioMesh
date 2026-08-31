package io.scenariomesh.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectedTestClassesTest {
    @Test
    void scansSelectedTopLevelClassesFromDependencyJar(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("dependency-tests.jar");
        try (OutputStream output = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(output)) {
            entry(archive, "example/DependencyTest.class");
            entry(archive, "example/DependencyTest$Nested.class");
            entry(archive, "example/package-info.class");
        }

        assertEquals(List.of("example.DependencyTest", "example.DependencyTest$Nested"),
                SelectedTestClasses.scan(List.of(jar), DiscoverySelection.all()));
    }

    private static void entry(JarOutputStream archive, String name) throws Exception {
        archive.putNextEntry(new JarEntry(name));
        archive.write(new byte[] {0});
        archive.closeEntry();
    }
}
