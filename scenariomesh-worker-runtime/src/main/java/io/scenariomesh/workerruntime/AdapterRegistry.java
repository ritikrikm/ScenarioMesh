package io.scenariomesh.workerruntime;

import io.scenariomesh.adapter.cucumberjunit4.CucumberJUnit4Adapter;
import io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter;
import io.scenariomesh.adapter.testng.TestNgAdapter;
import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Registry of ScenarioMesh adapters.
 *
 * <p>Built-in adapters remain available without service metadata. Additional product or
 * third-party adapters can be supplied through Java's standard ServiceLoader SPI on the
 * target runtime classpath. Duplicate ids are rejected rather than resolved by ordering.</p>
 */
public final class AdapterRegistry {
    private final List<ScenarioAdapter> adapters;
    private final Map<String, ScenarioAdapter> byId;

    public AdapterRegistry() {
        this(Thread.currentThread().getContextClassLoader());
    }

    AdapterRegistry(ClassLoader classLoader) {
        List<ScenarioAdapter> discovered = new ArrayList<>();
        discovered.add(new JUnitPlatformAdapter());
        discovered.add(new CucumberJUnit4Adapter());
        discovered.add(new TestNgAdapter());

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
                throw new IllegalStateException("Duplicate ScenarioMesh adapter id '" + id + "' from "
                        + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
            unique.add(adapter);
        }
        this.adapters = List.copyOf(unique);
        this.byId = Map.copyOf(index);
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
