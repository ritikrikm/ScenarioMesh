package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Ports.AdapterContext;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Detects executable framework families that are present in the Maven-selected
 * class set but have no ScenarioMesh adapter capable of owning them.
 *
 * <p>This is intentionally a gap detector, not another test engine. A detected
 * gap rejects takeover rather than allowing a green build that silently omits
 * native Maven tests. It is public so Maven preflight can prove ownership before
 * native Surefire/Failsafe is suppressed.</p>
 */
public final class FrameworkOwnershipGuard {
    private static final String JUNIT4_TEST = "org.junit.Test";
    private static final String JUNIT4_RUN_WITH = "org.junit.runner.RunWith";
    private static final String CUCUMBER_RUNNER = "cucumber";

    public void verifyNoUnsupportedExecutableFamilies(AdapterContext context) {
        if (!classPresent(JUNIT4_TEST, context.classLoader())) return;
        // Vintage is the target runtime's explicit JUnit 4 execution contract.
        if (hasVintageEngine(context.classLoader())) return;

        List<String> unsupportedJUnit4 = new ArrayList<>();
        List<String> uncertainClasses = new ArrayList<>();
        for (Path root : context.testRoots()) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (var files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                        .forEach(path -> inspect(path, root, context, unsupportedJUnit4, uncertainClasses));
            } catch (IOException exception) {
                throw new IllegalStateException("ScenarioMesh could not prove framework ownership under " + root, exception);
            }
        }

        if (!uncertainClasses.isEmpty()) {
            throw new IllegalStateException("ScenarioMesh could not prove complete framework ownership because selected test classes "
                    + String.join(", ", uncertainClasses)
                    + " could not be inspected without initialization. Native Maven execution is safer.");
        }
        if (!unsupportedJUnit4.isEmpty()) {
            throw new IllegalStateException("ScenarioMesh framework ownership gap: generic JUnit 4 executable tests are selected by Maven "
                    + "but the current runtime only owns JUnit Platform, Cucumber JUnit 4 and TestNG executions. Unowned classes: "
                    + String.join(", ", unsupportedJUnit4)
                    + ". ScenarioMesh will not silently omit them.");
        }
    }

    private void inspect(Path classFile,
                         Path root,
                         AdapterContext context,
                         List<String> unsupportedJUnit4,
                         List<String> uncertainClasses) {
        String className = className(root, classFile);
        if (className == null || className.contains("$")) return;
        if (!context.discoverySelection().matchesClassName(className)) return;
        try {
            Class<?> type = Class.forName(className, false, context.classLoader());
            if (isCucumberJUnit4Runner(type)) return;
            if (hasJUnit4TestMethod(type) || hasNonCucumberRunWith(type)) unsupportedJUnit4.add(className);
        } catch (ClassNotFoundException | LinkageError exception) {
            uncertainClasses.add(className);
        }
    }

    private boolean hasJUnit4TestMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                if (JUNIT4_TEST.equals(annotation.annotationType().getName())) return true;
            }
        }
        return false;
    }

    private boolean hasNonCucumberRunWith(Class<?> type) {
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            if (!JUNIT4_RUN_WITH.equals(annotation.annotationType().getName())) continue;
            String text = annotation.toString().toLowerCase(java.util.Locale.ROOT);
            return !text.contains(CUCUMBER_RUNNER);
        }
        return false;
    }

    private boolean isCucumberJUnit4Runner(Class<?> type) {
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            if (!JUNIT4_RUN_WITH.equals(annotation.annotationType().getName())) continue;
            String text = annotation.toString().toLowerCase(java.util.Locale.ROOT);
            return text.contains(CUCUMBER_RUNNER);
        }
        return false;
    }

    private boolean classPresent(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private boolean hasVintageEngine(ClassLoader loader) {
        try {
            Class<?> engineType = Class.forName("org.junit.platform.engine.TestEngine", false, loader);
            for (Object engine : ServiceLoader.load(engineType, loader)) {
                Object id = engineType.getMethod("getId").invoke(engine);
                if ("junit-vintage".equals(id)) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return false;
        }
    }

    private String className(Path root, Path classFile) {
        try {
            String relative = root.toAbsolutePath().normalize().relativize(classFile.toAbsolutePath().normalize()).toString();
            if (!relative.endsWith(".class")) return null;
            relative = relative.substring(0, relative.length() - 6);
            return relative.replace('/', '.').replace('\\', '.');
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
