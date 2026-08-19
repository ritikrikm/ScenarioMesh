package io.scenariomesh.workerruntime;

import io.scenariomesh.adapter.cucumberjunit4.CucumberJUnit4Adapter;
import io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter;
import io.scenariomesh.adapter.testng.TestNgAdapter;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdapterRegistry {
    private final List<ScenarioAdapter> adapters;
    private final Map<String, ScenarioAdapter> byId;
    public AdapterRegistry(){this.adapters=List.of(new JUnitPlatformAdapter(),new CucumberJUnit4Adapter(),new TestNgAdapter());Map<String,ScenarioAdapter> index=new LinkedHashMap<>();for(ScenarioAdapter adapter:adapters)index.put(adapter.id(),adapter);this.byId=Map.copyOf(index);}
    public List<ScenarioAdapter> available(ClassLoader classLoader){return adapters.stream().filter(adapter->adapter.isAvailable(classLoader)).toList();}
    public ScenarioAdapter required(String id){ScenarioAdapter adapter=byId.get(id);if(adapter==null)throw new IllegalArgumentException("No ScenarioMesh adapter registered for: "+id);return adapter;}
}
