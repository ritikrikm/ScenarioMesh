package io.scenariomesh.workerruntime;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Target-owned execution realm with control-plane parent isolation. */
public final class TargetRuntimeClassLoader extends URLClassLoader {
    private static final String JUNIT_ENGINE_SERVICE = "META-INF/services/org.junit.platform.engine.TestEngine";
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "org.w3c.dom.", "org.xml.sax.",
            "io.scenariomesh.core.", "io.scenariomesh.protocol.", "io.scenariomesh.config.",
            "io.scenariomesh.controljson.", "io.scenariomesh.workerruntime.", "io.scenariomesh.scheduler.",
            "io.scenariomesh.coordinator.", "io.scenariomesh.reporting.", "io.scenariomesh.observability.",
            "io.scenariomesh.maven.");

    static { registerAsParallelCapable(); }

    private TargetRuntimeClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }

    public static TargetRuntimeClassLoader fromClasspath(List<Path> classpath, ClassLoader parent) {
        if (parent == null) throw new IllegalArgumentException("target runtime parent classloader is required");
        if (classpath == null || classpath.isEmpty()) throw new IllegalArgumentException("target runtime classpath must not be empty");
        List<URL> urls = new ArrayList<>();
        for (Path entry : classpath) {
            if (entry == null) continue;
            try { urls.add(entry.toAbsolutePath().normalize().toUri().toURL()); }
            catch (Exception exception) { throw new IllegalArgumentException("Invalid target runtime classpath entry '" + entry + "'", exception); }
        }
        if (urls.isEmpty()) throw new IllegalArgumentException("target runtime classpath must not be empty");
        return new TargetRuntimeClassLoader(urls.toArray(URL[]::new), parent);
    }

    public static TargetRuntimeClassLoader fromCurrentClasspath(ClassLoader parent) {
        String raw = System.getProperty("java.class.path", "");
        List<Path> entries = new ArrayList<>();
        if (!raw.isBlank()) {
            for (String entry : raw.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry == null || entry.isBlank()) continue;
                entries.add(Path.of(entry));
            }
        }
        return fromClasspath(entries, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && childFirst(name)) {
                try { loaded = findClass(name); }
                catch (ClassNotFoundException ignored) { }
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (!JUNIT_ENGINE_SERVICE.equals(name)) return super.getResources(name);
        List<URL> target = Collections.list(findResources(name));
        if (!target.isEmpty()) return Collections.enumeration(target);
        return getParent() == null ? Collections.emptyEnumeration() : getParent().getResources(name);
    }

    @Override
    public URL getResource(String name) {
        if (!JUNIT_ENGINE_SERVICE.equals(name)) return super.getResource(name);
        URL target = findResource(name);
        if (target != null) return target;
        return getParent() == null ? null : getParent().getResource(name);
    }

    private boolean childFirst(String name) {
        if (name.startsWith("io.scenariomesh.adapter.")) return true;
        for (String prefix : PARENT_FIRST_PREFIXES) if (name.startsWith(prefix)) return false;
        return true;
    }
}
