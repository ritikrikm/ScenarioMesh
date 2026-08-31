package io.scenariomesh.workerruntime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Owns the target execution class loader for one worker/discovery process.
 * In JPMS mode the JVM launcher has already resolved the target module graph, so the application
 * loader must be used; creating a URLClassLoader over the same entries would silently bypass module boundaries.
 */
final class TargetRuntimeLoaderScope implements AutoCloseable {
    static final String MODULE_PATH_PROPERTY = "scenariomesh.target.modulePath";

    private final ClassLoader loader;
    private final TargetRuntimeClassLoader closeableLoader;

    private TargetRuntimeLoaderScope(ClassLoader loader, TargetRuntimeClassLoader closeableLoader) {
        this.loader = loader;
        this.closeableLoader = closeableLoader;
    }

    static TargetRuntimeLoaderScope open(List<Path> targetClasspath, ClassLoader controlLoader) {
        if (Boolean.getBoolean(MODULE_PATH_PROPERTY)) {
            ClassLoader applicationLoader = ClassLoader.getSystemClassLoader();
            if (applicationLoader == null) {
                throw new IllegalStateException("JPMS target execution requires the JVM application class loader");
            }
            return new TargetRuntimeLoaderScope(applicationLoader, null);
        }
        TargetRuntimeClassLoader loader = TargetRuntimeClassLoader.fromClasspath(targetClasspath, controlLoader);
        return new TargetRuntimeLoaderScope(loader, loader);
    }

    ClassLoader loader() { return loader; }

    @Override
    public void close() throws IOException {
        if (closeableLoader != null) closeableLoader.close();
    }
}
