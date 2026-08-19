package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class DiscoveryMain {
    private DiscoveryMain() {}
    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        AdapterRegistry registry = new AdapterRegistry();
        List<ScenarioAdapter> available = registry.available(classLoader);
        if (available.isEmpty()) throw new IllegalStateException("ScenarioMesh found no supported test adapter on the test runtime classpath");
        Map<String,String> properties=new HashMap<>();
        System.getProperties().forEach((key,value)->properties.put(String.valueOf(key),String.valueOf(value)));
        AdapterContext context=new AdapterContext(classLoader,parsed.testRoots,properties);
        List<ScenarioTask> tasks=new ArrayList<>();List<String> detected=new ArrayList<>();
        for(ScenarioAdapter adapter:available){List<ScenarioTask> discovered=adapter.discover(context);if(!discovered.isEmpty()){detected.add(adapter.id());tasks.addAll(discovered);}}
        if(tasks.isEmpty()) throw new IllegalStateException("ScenarioMesh detected supported libraries but discovered zero executable tests. Check the repository's existing filters and test configuration.");
        Files.createDirectories(parsed.output.getParent());
        ObjectMapper mapper=JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(parsed.output.toFile(),new DiscoveryResult(List.copyOf(detected),List.copyOf(tasks)));
    }
    public record DiscoveryResult(List<String> adapters,List<ScenarioTask> tasks) {}
    private static final class Arguments {
        private final Path output; private final List<Path> testRoots;
        private Arguments(Path output,List<Path> testRoots){this.output=output;this.testRoots=testRoots;}
        private static Arguments parse(String[] args){Path output=null;List<Path> roots=new ArrayList<>();for(int i=0;i<args.length;i++){switch(args[i]){case "--output"->output=Path.of(requireValue(args,++i,"--output"));case "--test-root"->roots.add(Path.of(requireValue(args,++i,"--test-root")));default->throw new IllegalArgumentException("Unknown discovery argument: "+args[i]);}}if(output==null)throw new IllegalArgumentException("--output is required");return new Arguments(output,List.copyOf(roots));}
        private static String requireValue(String[] args,int index,String name){if(index>=args.length)throw new IllegalArgumentException(name+" requires a value");return args[index];}
    }
}
