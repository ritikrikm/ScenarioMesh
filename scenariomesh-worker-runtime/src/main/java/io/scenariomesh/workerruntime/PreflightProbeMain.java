package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import org.junit.platform.engine.UniqueId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs Maven ownership preflight inside the exact JVM selected for target tests. */
public final class PreflightProbeMain {
    private PreflightProbeMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        DiscoverySelection selection = new DiscoverySelection(parsed.includes, parsed.excludes);
        AdapterContext context = new AdapterContext(loader, parsed.testRoots, properties, selection);
        AdapterRegistry registry = new AdapterRegistry(loader);

        new FrameworkOwnershipGuard().verifyNoUnsupportedExecutableFamilies(context);
        Set<String> adapterOwnedEngines = new LinkedHashSet<>();
        for (ScenarioAdapter adapter : registry.available(loader)) {
            adapterOwnedEngines.addAll(adapter.capabilities().junitPlatformEngineIds());
        }
        ExecutionBackendInventory.Inventory inventory = ExecutionBackendInventory.inspect(
                loader, parsed.testRoots, parsed.includes, parsed.excludes, adapterOwnedEngines);
        RuntimeRequirements requirements = runtimeRequirements(context, registry);

        Files.createDirectories(parsed.output.getParent());
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(parsed.output.toFile(),
                new ProbeResult(inventory.ownership().name(), inventory.summary(),
                        requirements.adapterIds(), requirements.engineIds()));
    }

    static RuntimeRequirements runtimeRequirements(AdapterContext context, AdapterRegistry registry) throws Exception {
        Set<String> adapterIds = new LinkedHashSet<>();
        Set<String> engineIds = new LinkedHashSet<>();
        for (ScenarioAdapter adapter : registry.available(context.classLoader())) {
            List<ScenarioTask> tasks = adapter.discover(context);
            if (tasks.isEmpty()) continue;
            adapterIds.add(adapter.id());
            if ("junit-platform".equals(adapter.id())) {
                for (ScenarioTask task : tasks) {
                    try {
                        UniqueId.parse(task.selector()).getEngineId().ifPresent(engineIds::add);
                    } catch (RuntimeException exception) {
                        throw new IllegalStateException("Selected JUnit Platform task has an invalid UniqueId selector: "
                                + task.selector(), exception);
                    }
                }
            }
        }
        return new RuntimeRequirements(Set.copyOf(adapterIds), Set.copyOf(engineIds));
    }

    public record ProbeResult(String ownership, String summary,
                              Set<String> requiredAdapterIds, Set<String> requiredEngineIds) {
        public ProbeResult {
            requiredAdapterIds = Set.copyOf(requiredAdapterIds == null ? Set.of() : requiredAdapterIds);
            requiredEngineIds = Set.copyOf(requiredEngineIds == null ? Set.of() : requiredEngineIds);
        }
    }

    record RuntimeRequirements(Set<String> adapterIds, Set<String> engineIds) {
        RuntimeRequirements {
            adapterIds = Set.copyOf(adapterIds == null ? Set.of() : adapterIds);
            engineIds = Set.copyOf(engineIds == null ? Set.of() : engineIds);
        }
    }

    private static final class Arguments {
        private final Path output;
        private final List<Path> testRoots;
        private final List<String> includes;
        private final List<String> excludes;

        private Arguments(Path output, List<Path> testRoots, List<String> includes, List<String> excludes) {
            this.output = output;
            this.testRoots = testRoots;
            this.includes = includes;
            this.excludes = excludes;
        }

        private static Arguments parse(String[] args) {
            Path output = null;
            List<Path> roots = new ArrayList<>();
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(require(args, ++i, "--output"));
                    case "--test-root" -> roots.add(Path.of(require(args, ++i, "--test-root")));
                    case "--include-class-regex" -> includes.add(require(args, ++i, "--include-class-regex"));
                    case "--exclude-class-regex" -> excludes.add(require(args, ++i, "--exclude-class-regex"));
                    default -> throw new IllegalArgumentException("Unknown preflight argument: " + args[i]);
                }
            }
            if (output == null) throw new IllegalArgumentException("--output is required");
            return new Arguments(output, List.copyOf(roots), List.copyOf(includes), List.copyOf(excludes));
        }

        private static String require(String[] args, int index, String name) {
            if (index >= args.length) throw new IllegalArgumentException(name + " requires a value");
            return args[index];
        }
    }
}
