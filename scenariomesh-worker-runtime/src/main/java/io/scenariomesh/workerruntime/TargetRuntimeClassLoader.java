package io.scenariomesh.workerruntime;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Execution realm for target-owned test adapters and third-party adapter providers.
 *
 * <p>ScenarioMesh core/protocol/control classes remain parent-owned. Built-in adapter
 * implementations are child-first so their framework linkage can live in the target
 * execution realm as the control-plane classpath is progressively separated.</p>
 */
public final class TargetRuntimeClassLoader extends URLClassLoader {
    private static final List<String> CHILD_FIRST_PREFIXES = List.of(
            "io.scenariomesh.adapter.");

    static {
        registerAsParallelCapable();
    }

    private TargetRuntimeClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public static TargetRuntimeClassLoader fromCurrentClasspath(ClassLoader parent) {
        String raw = System.getProperty("java.class.path", "");
        List<URL> urls = new ArrayList<>();
        if (!raw.isBlank()) {
            for (String entry : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry == null || entry.isBlank()) continue;
                try {
                    urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
                } catch (Exception exception) {
                    throw new IllegalArgumentException("Invalid target runtime classpath entry '" + entry + "'", exception);
                }
            }
        }
        return new TargetRuntimeClassLoader(urls.toArray(URL[]::new), parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && childFirst(name)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    // Fall through to the parent/shared contract realm.
                }
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private boolean childFirst(String name) {
        for (String prefix : CHILD_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
