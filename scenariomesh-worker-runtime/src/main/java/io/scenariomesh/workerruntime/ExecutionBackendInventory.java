package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Framework-neutral facade for runtime backend inventory.
 *
 * <p>JUnit Platform probing is loaded reflectively from the target execution realm so worker-runtime
 * does not bind to ScenarioMesh's own JUnit Platform version. If the adapter/probe is absent there
 * is simply no JUnit Platform backend to inventory.</p>
 */
public final class ExecutionBackendInventory {
    private static final String JUNIT_PROBE =
            "io.scenariomesh.adapter.junitplatform.JUnitPlatformBackendProbe";

    private ExecutionBackendInventory() {}

    public static Inventory inspect(ClassLoader targetClassLoader, List<Path> testRoots,
                                    List<String> includeClassNameRegexes, List<String> excludeClassNameRegexes) {
        return inspect(targetClassLoader, testRoots, includeClassNameRegexes, excludeClassNameRegexes,
                adapterOwnedEngineIds(targetClassLoader));
    }

    public static Inventory inspect(ClassLoader targetClassLoader, List<Path> testRoots,
                                    List<String> includeClassNameRegexes, List<String> excludeClassNameRegexes,
                                    Set<String> adapterOwnedEngineIds) {
        try {
            Class<?> probe = Class.forName(JUNIT_PROBE, true, targetClassLoader);
            Method inspect = probe.getMethod("inspect", ClassLoader.class, List.class, List.class, List.class, Set.class);
            Object raw = inspect.invoke(null, targetClassLoader,
                    List.copyOf(testRoots == null ? List.of() : testRoots),
                    List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes),
                    List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes),
                    Set.copyOf(adapterOwnedEngineIds == null ? Set.of() : adapterOwnedEngineIds));
            return decode(raw);
        } catch (ClassNotFoundException absent) {
            return new Inventory(Ownership.NOT_DETECTED, List.of(),
                    "JUnit Platform backend probe is not present in the target execution realm");
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new Inventory(Ownership.DETECTED_NOT_OWNABLE, List.of(),
                    "JUnit Platform backend probing failed across the adapter boundary: " + message(rootCause(failure)));
        }
    }

    private static Inventory decode(Object raw) throws ReflectiveOperationException {
        if (raw == null) throw new IllegalStateException("target backend probe returned null");
        Class<?> type = raw.getClass();
        Ownership ownership = Ownership.valueOf(String.valueOf(type.getMethod("ownership").invoke(raw)));
        String reason = String.valueOf(type.getMethod("reason").invoke(raw));
        Object rawBackends = type.getMethod("backends").invoke(raw);
        if (!(rawBackends instanceof List<?> values)) {
            throw new IllegalStateException("target backend probe returned a non-list backend collection");
        }
        List<Backend> backends = new ArrayList<>();
        for (Object value : values) backends.add(decodeBackend(value));
        return new Inventory(ownership, List.copyOf(backends), reason);
    }

    private static Backend decodeBackend(Object raw) throws ReflectiveOperationException {
        Class<?> type = raw.getClass();
        String id = String.valueOf(type.getMethod("id").invoke(raw));
        String provider = String.valueOf(type.getMethod("provider").invoke(raw));
        long leaves = ((Number) type.getMethod("executableLeaves").invoke(raw)).longValue();
        BackendOwnership ownership = BackendOwnership.valueOf(String.valueOf(type.getMethod("ownership").invoke(raw)));
        ExecutionGranularity granularity = ExecutionGranularity.valueOf(String.valueOf(type.getMethod("granularity").invoke(raw)));
        Object rawCapabilities = type.getMethod("capabilities").invoke(raw);
        Set<Capability> capabilities = new LinkedHashSet<>();
        if (rawCapabilities instanceof Set<?> values) {
            for (Object value : values) capabilities.add(Capability.valueOf(String.valueOf(value)));
        }
        return new Backend(id, provider, leaves, ownership, granularity, Set.copyOf(capabilities));
    }

    private static Set<String> adapterOwnedEngineIds(ClassLoader classLoader) {
        Set<String> ids = new LinkedHashSet<>();
        AdapterRegistry registry = new AdapterRegistry(classLoader);
        for (ScenarioAdapter adapter : registry.available(classLoader)) {
            ids.addAll(adapter.capabilities().junitPlatformEngineIds());
        }
        return Set.copyOf(ids);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    public enum Ownership { OWNABLE, DETECTED_NOT_OWNABLE, NOT_DETECTED }
    public enum BackendOwnership { OWNABLE, DETECTED_NOT_OWNABLE }
    public enum ExecutionGranularity { LEAF, CLASS, CONTAINER_OR_RUN, UNKNOWN }

    public enum Capability {
        DISCOVERY, STABLE_LEAF_IDENTITY, ISOLATED_LEAF_EXECUTION,
        LIFECYCLE_SCOPED_EXECUTION, FILTER_EQUIVALENCE, REPORT_EQUIVALENCE, RETRY_SAFE
    }

    public record Backend(String id, String provider, long executableLeaves,
                          BackendOwnership ownership, ExecutionGranularity granularity,
                          Set<Capability> capabilities) {
        public Backend { capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities); }
    }

    public record Inventory(Ownership ownership, List<Backend> backends, String reason) {
        public Inventory { backends = List.copyOf(backends == null ? List.of() : backends); }
        public String summary() {
            if (backends.isEmpty()) return ownership + " (" + reason + ")";
            StringBuilder value = new StringBuilder(ownership.name()).append(" [");
            for (int i = 0; i < backends.size(); i++) {
                Backend backend = backends.get(i);
                if (i > 0) value.append(", ");
                value.append(backend.id()).append(":leaves=").append(backend.executableLeaves())
                        .append(":").append(backend.ownership())
                        .append(":granularity=").append(backend.granularity());
            }
            return value.append("] (").append(reason).append(')').toString();
        }
    }
}
