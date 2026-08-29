package io.scenariomesh.workerruntime;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.core.TaskMetadata;
import io.scenariomesh.maven.selection.SurefireTestSelection;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Runs Maven ownership preflight inside the exact JVM selected for target tests. */
public final class PreflightProbeMain {
    private static final String SET_SEPARATOR = "\u001f";

    private PreflightProbeMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ClassLoader controlLoader = PreflightProbeMain.class.getClassLoader();
        String encoded = System.getProperty(TargetClasspathDescriptor.SYSTEM_PROPERTY);
        List<Path> targetClasspath = encoded == null || encoded.isBlank()
                ? currentClasspath()
                : TargetClasspathDescriptor.decodeInline(encoded);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (TargetRuntimeClassLoader loader = TargetRuntimeClassLoader.fromClasspath(targetClasspath, controlLoader)) {
            thread.setContextClassLoader(loader);
            run(parsed, loader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void run(Arguments parsed, ClassLoader loader) throws Exception {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        properties.remove(TargetClasspathDescriptor.SYSTEM_PROPERTY);
        String testListExpression = properties.remove(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION);
        DiscoverySelection selection = new DiscoverySelection(parsed.includes, parsed.excludes, testListExpression);
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
        writeResult(parsed.output, new ProbeResult(
                inventory.ownership().name(), inventory.summary(),
                requirements.adapterIds(), requirements.engineIds()));
    }

    public static void writeResult(Path output, ProbeResult result) throws Exception {
        Properties values = new Properties();
        values.setProperty("ownership", result.ownership());
        values.setProperty("summary", result.summary() == null ? "" : result.summary());
        values.setProperty("requiredAdapterIds", String.join(SET_SEPARATOR, result.requiredAdapterIds()));
        values.setProperty("requiredEngineIds", String.join(SET_SEPARATOR, result.requiredEngineIds()));
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            values.store(writer, "ScenarioMesh selected-JVM ownership probe");
        }
    }

    public static ProbeResult readResult(Path input) throws Exception {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        return new ProbeResult(
                require(values, "ownership"),
                values.getProperty("summary", ""),
                splitSet(values.getProperty("requiredAdapterIds", "")),
                splitSet(values.getProperty("requiredEngineIds", "")));
    }

    private static Set<String> splitSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.of(value.split(SET_SEPARATOR, -1));
    }

    private static String require(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Preflight result is missing required field '" + key + "'");
        }
        return value;
    }

    static RuntimeRequirements runtimeRequirements(AdapterContext context, AdapterRegistry registry) throws Exception {
        Set<String> adapterIds = new LinkedHashSet<>();
        Set<String> engineIds = new LinkedHashSet<>();
        SurefireTestSelection methodSelection = context.discoverySelection().hasTestListExpression()
                ? new SurefireTestSelection(context.discoverySelection().testListExpression()) : null;
        for (ScenarioAdapter adapter : registry.available(context.classLoader())) {
            List<ScenarioTask> tasks = adapter.discover(context);
            if (methodSelection != null && "testng".equals(adapter.id())) {
                tasks = tasks.stream().filter(task -> {
                    String className = task.metadata().get("className");
                    String methodName = task.metadata().get("methodName");
                    return className == null || methodName == null || methodSelection.matches(className, methodName);
                }).toList();
            }
            if (tasks.isEmpty()) continue;
            adapterIds.add(adapter.id());
            if ("junit-platform".equals(adapter.id())) {
                for (ScenarioTask task : tasks) {
                    String engineId = task.metadata().get(TaskMetadata.REQUIRED_ENGINE_ID);
                    if (engineId == null || engineId.isBlank()) {
                        throw new IllegalStateException("Selected JUnit Platform task did not publish a required engine id: "
                                + task.selector());
                    }
                    engineIds.add(engineId);
                }
            }
        }
        return new RuntimeRequirements(Set.copyOf(adapterIds), Set.copyOf(engineIds));
    }

    private static List<Path> currentClasspath() {
        String raw = System.getProperty("java.class.path", "");
        if (raw.isBlank()) throw new IllegalStateException("java.class.path is empty and no target classpath was supplied");
        return java.util.Arrays.stream(raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> value != null && !value.isBlank()).map(Path::of).toList();
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
