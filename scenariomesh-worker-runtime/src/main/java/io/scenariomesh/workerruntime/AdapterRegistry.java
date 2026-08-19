package io.scenariomesh.workerruntime;

import io.scenariomesh.adapter.cucumberjunit4.CucumberJUnit4Adapter;
import io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter;
import io.scenariomesh.adapter.testng.TestNgAdapter;
import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of adapter implementations shipped by this ScenarioMesh runtime. */
public final class AdapterRegistry {
    private final List<ScenarioAdapter> adapters;
    private final Map<String, ScenarioAdapter> byId;

    public AdapterRegistry() {
        this.adapters = List.of(
                new JUnitPlatformAdapter(),
                new CucumberJUnit4Adapter(),
                new TestNgAdapter());
        Map<String, ScenarioAdapter> index = new LinkedHashMap<>();
        for (ScenarioAdapter adapter : adapters) {
            ScenarioAdapter previous = index.put(adapter.id(), adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate ScenarioMesh adapter id: " + adapter.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    public List<ScenarioAdapter> all() {
        return adapters;
    }

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
