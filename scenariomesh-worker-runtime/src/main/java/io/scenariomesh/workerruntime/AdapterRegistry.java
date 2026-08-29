package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Registry of ScenarioMesh adapters.
 *
 * <p>Built-in adapters are resolved through the supplied runtime classloader rather than being
 * constructed through worker-runtime's defining loader. This keeps the SPI boundary ready for a
 * split control-plane/target-execution classloader: adapter implementation bytecode can live with
 * the target framework versions while the shared ScenarioAdapter contract remains parent-owned.</p>
 *
 * <p>Additional product or third-party adapters can be supplied through Java's standard
 * ServiceLoader SPI. Duplicate ids are rejected rather than resolved by ordering.</p>
 */
public final class AdapterRegistry {
    private static final List<String> BUILT_IN_ADAPTER_CLASSES = List.of(
            "io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter",
            "io.scenariomesh.adapter.cucumberjunit4.CucumberJUnit4Adapter",
            "io.scenariomesh.adapter.testng.TestNgAdapter");

    private final List<ScenarioAdapter> adapters;
    private final Map<String, ScenarioAdapter> byId;

    public AdapterRegistry() {
        this(Thread.currentThread().getContextClassLoader());
    }

    AdapterRegistry(ClassLoader classLoader) {
        if (classLoader == null) throw new IllegalArgumentException("adapter runtime classloader is required");
        List<ScenarioAdapter> discovered = new ArrayList<>();
        for (String className : BUILT_IN_ADAPTER_CLASSES) {
            ScenarioAdapter adapter = instantiate(className, classLoader);
            if (adapter != null) discovered.add(adapter);
        }

        try {
            ServiceLoader.load(ScenarioAdapter.class, classLoader).stream()
                    .map(ServiceLoader.Provider::get)
                    .forEach(discovered::add);
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("ScenarioMesh adapter SPI could not load a provider: " + error.getMessage(), error);
        }

        Map<String, ScenarioAdapter> index = new LinkedHashMap<>();
        List<ScenarioAdapter> unique = new ArrayList<>();
        for (ScenarioAdapter adapter : discovered) {
            String id = adapter.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("ScenarioMesh adapter SPI provider " + adapter.getClass().getName()
                        + " returned a blank adapter id");
            }
            ScenarioAdapter previous = index.putIfAbsent(id, adapter);
            if (previous != null) {
                // A built-in adapter may also carry ServiceLoader metadata. Treat the exact same
                // implementation class as one provider, but fail closed for genuinely competing ids.
                if (previous.getClass().getName().equals(adapter.getClass().getName())) continue;
                throw new IllegalStateException("Duplicate ScenarioMesh adapter id '" + id + "' from "
                        + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
            unique.add(adapter);
        }
        this.adapters = List.copyOf(unique);
        this.byId = Map.copyOf(index);
    }

    private ScenarioAdapter instantiate(String className, ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(className, true, classLoader);
            if (!ScenarioAdapter.class.isAssignableFrom(type)) {
                throw new IllegalStateException("Built-in ScenarioMesh adapter " + className
                        + " was loaded with an incompatible ScenarioAdapter contract; core must be parent-owned");
            }
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ScenarioAdapter) constructor.newInstance();
        } catch (ClassNotFoundException missing) {
            // Runtime packaging may intentionally omit an adapter family. Availability remains a
            // capability, not a worker startup requirement.
            return null;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Could not load built-in ScenarioMesh adapter " + className
                    + " through the runtime classloader: " + message(failure), failure);
        }
    }

    private String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    public List<ScenarioAdapter> all() { return adapters; }

    public List<ScenarioAdapter> available(ClassLoader classLoader) {
        return adapters.stream().filter(adapter -> adapter.isAvailable(classLoader)).toList();
    }

    public ScenarioAdapter required(String id) {
        ScenarioAdapter adapter = byId.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException("No ScenarioMesh adapter registered for '" + id
                    + "'. Registered adapters: " + String.join(", ", byId.keySet()));
        }
        return adapter;
    }
}
