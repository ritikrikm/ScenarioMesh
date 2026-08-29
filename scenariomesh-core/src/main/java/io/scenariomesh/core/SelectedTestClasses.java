package io.scenariomesh.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves compiled test classes selected through the framework-neutral core boundary.
 *
 * <p>Outer build integrations are responsible for translating their native include/exclude
 * syntax into {@link DiscoverySelection}. Core only scans compiled roots and applies that
 * canonical selection; adapters therefore never need to understand build-tool expressions.</p>
 */
public final class SelectedTestClasses {
    private SelectedTestClasses() {}

    public static List<String> scan(List<Path> testRoots, DiscoverySelection selection) {
        if (testRoots == null || testRoots.isEmpty()) return List.of();
        DiscoverySelection effective = selection == null ? DiscoverySelection.all() : selection;
        Set<String> selected = new LinkedHashSet<>();
        for (Path root : testRoots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> className(root, path))
                        .filter(SelectedTestClasses::candidateClass)
                        .filter(effective::matchesClassName)
                        .sorted()
                        .forEach(selected::add);
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to scan compiled test classes under " + root, exception);
            }
        }
        return List.copyOf(selected);
    }

    private static String className(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

    private static boolean candidateClass(String className) {
        return !"module-info".equals(className)
                && !className.endsWith(".module-info")
                && !"package-info".equals(className)
                && !className.endsWith(".package-info");
    }
}
