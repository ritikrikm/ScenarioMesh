package io.scenariomesh.workerruntime;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.config.ScenarioMeshConfig.AdapterMismatchPolicy;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.maven.selection.SurefireTestSelection;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate-process framework discovery entry point. */
public final class DiscoveryMain {
    private DiscoveryMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Thread thread = Thread.currentThread();
        ClassLoader controlLoader = DiscoveryMain.class.getClassLoader();
        String encoded = System.getProperty(TargetClasspathDescriptor.SYSTEM_PROPERTY);
        List<Path> targetClasspath = encoded == null || encoded.isBlank()
                ? currentClasspath()
                : TargetClasspathDescriptor.decodeInline(encoded);
        ClassLoader previous = thread.getContextClassLoader();
        try (TargetRuntimeClassLoader targetLoader = TargetRuntimeClassLoader.fromClasspath(targetClasspath, controlLoader)) {
            thread.setContextClassLoader(targetLoader);
            discover(parsed, targetLoader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void discover(Arguments parsed, ClassLoader classLoader) throws Exception {
        AdapterRegistry registry = new AdapterRegistry(classLoader);
        Map<String, String> properties = systemProperties();
        String testListExpression = properties.remove(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION);
        DiscoverySelection discoverySelection = new DiscoverySelection(
                parsed.includeClassNameRegexes, parsed.excludeClassNameRegexes, testListExpression);
        AdapterContext context = new AdapterContext(classLoader, parsed.testRoots, properties, discoverySelection);

        ExecutionBackendInventory.Inventory backendInventory = ExecutionBackendInventory.inspect(
                classLoader,
                parsed.testRoots,
                parsed.includeClassNameRegexes,
                parsed.excludeClassNameRegexes);
        if (backendInventory.ownership() == ExecutionBackendInventory.Ownership.DETECTED_NOT_OWNABLE) {
            throw new IllegalStateException("ScenarioMesh detected an executable JUnit Platform backend that it cannot safely own: "
                    + backendInventory.summary() + ". Native Maven execution is safer.");
        }

        new FrameworkOwnershipGuard().verifyNoUnsupportedExecutableFamilies(context);

        Map<String, List<ScenarioTask>> discoveredByAdapter = new LinkedHashMap<>();
        List<AdapterEvidence> evidence = new ArrayList<>();
        List<String> autoDiscoveryErrors = new ArrayList<>();

        for (ScenarioAdapter adapter : registry.all()) {
            boolean available;
            try {
                available = adapter.isAvailable(classLoader);
            } catch (RuntimeException exception) {
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), false, 0,
                        "availability probe failed: " + message(exception)));
                autoDiscoveryErrors.add(adapter.id() + ": availability probe failed: " + message(exception));
                continue;
            }

            if (!available) {
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), false, 0, null));
                continue;
            }

            try {
                List<ScenarioTask> discovered = applyTestListSelection(
                        adapter.id(), adapter.discover(context), testListExpression);
                discoveredByAdapter.put(adapter.id(), List.copyOf(discovered));
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), true, discovered.size(), null));
            } catch (Exception | LinkageError exception) {
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), true, 0,
                        "discovery failed: " + message(exception)));
                autoDiscoveryErrors.add(adapter.id() + ": discovery failed: " + message(exception));
            }
        }

        Selection selection = select(parsed, registry, discoveredByAdapter, evidence, autoDiscoveryErrors);
        DiscoveryResultCodec.write(
                parsed.output,
                new DiscoveryResult(
                        selection.adapterIds(),
                        List.copyOf(evidence),
                        selection.warnings(),
                        selection.tasks()));
    }

    private static List<ScenarioTask> applyTestListSelection(
            String adapterId, List<ScenarioTask> tasks, String expression) {
        if (expression == null || expression.isBlank() || tasks.isEmpty()) return List.copyOf(tasks);
        if (!"testng".equals(adapterId)) return List.copyOf(tasks);
        SurefireTestSelection selection = new SurefireTestSelection(expression);
        return tasks.stream().filter(task -> {
            String className = task.metadata().get("className");
            String methodName = task.metadata().get("methodName");
            if (className == null || methodName == null) return true;
            return selection.matches(className, methodName);
        }).toList();
    }

    private static Selection select(
            Arguments parsed,
            AdapterRegistry registry,
            Map<String, List<ScenarioTask>> discoveredByAdapter,
            List<AdapterEvidence> evidence,
            List<String> autoDiscoveryErrors) {
        Map<String, List<ScenarioTask>> candidates = new LinkedHashMap<>();
        discoveredByAdapter.forEach((id, tasks) -> {
            if (!tasks.isEmpty()) candidates.put(id, tasks);
        });

        if (ScenarioMeshConfig.AUTO_ADAPTER.equals(parsed.adapter)) {
            if (!autoDiscoveryErrors.isEmpty()) {
                throw new IllegalStateException("ScenarioMesh auto-detection could not safely evaluate every available adapter. "
                        + String.join("; ", autoDiscoveryErrors) + evidenceText(evidence));
            }
            if (candidates.isEmpty()) {
                throw new IllegalStateException("ScenarioMesh detected no adapter with executable tests." + evidenceText(evidence));
            }
            List<ScenarioTask> combined = combineWithUniqueOwnership(candidates, evidence);
            return new Selection(List.copyOf(candidates.keySet()), combined, List.of());
        }

        registry.required(parsed.adapter);
        List<ScenarioTask> configuredTasks = discoveredByAdapter.get(parsed.adapter);
        if (configuredTasks != null && !configuredTasks.isEmpty()) {
            List<String> warnings = candidates.size() > 1
                    ? List.of("Multiple adapters discovered executable tests (" + String.join(", ", candidates.keySet())
                    + "); explicit configuration selected '" + parsed.adapter + "'.")
                    : List.of();
            return new Selection(List.of(parsed.adapter), configuredTasks, warnings);
        }

        String mismatch = "Configured adapter '" + parsed.adapter
                + "' did not discover executable tests." + evidenceText(evidence);
        if (parsed.mismatchPolicy == AdapterMismatchPolicy.USE_DETECTED
                && candidates.size() == 1
                && autoDiscoveryErrors.isEmpty()) {
            Map.Entry<String, List<ScenarioTask>> detected = candidates.entrySet().iterator().next();
            return new Selection(
                    List.of(detected.getKey()),
                    detected.getValue(),
                    List.of(mismatch + " Using uniquely detected adapter '" + detected.getKey()
                            + "' because execution.adapterMismatchPolicy=use-detected."));
        }
        throw new IllegalStateException(mismatch + " Policy is " + parsed.mismatchPolicy.externalValue()
                + "; ScenarioMesh will not guess which tests to run.");
    }

    private static List<ScenarioTask> combineWithUniqueOwnership(
            Map<String, List<ScenarioTask>> candidates, List<AdapterEvidence> evidence) {
        Map<String, String> ownerByLogicalTest = new LinkedHashMap<>();
        List<ScenarioTask> combined = new ArrayList<>();
        for (Map.Entry<String, List<ScenarioTask>> candidate : candidates.entrySet()) {
            for (ScenarioTask task : candidate.getValue()) {
                String key = logicalKey(task);
                String previous = ownerByLogicalTest.putIfAbsent(key, candidate.getKey());
                if (previous != null && !previous.equals(candidate.getKey())) {
                    throw new IllegalStateException("ScenarioMesh cannot prove unique framework ownership for logical test '"
                            + key + "': adapters '" + previous + "' and '" + candidate.getKey()
                            + "' both discovered it. Native Maven execution is safer." + evidenceText(evidence));
                }
                combined.add(task);
            }
        }
        return List.copyOf(combined);
    }

    static String logicalKey(ScenarioTask task) {
        String className = task.metadata().get("className");
        String methodName = task.metadata().get("methodName");
        if (className != null && !className.isBlank()) {
            return className + "#" + (methodName == null ? "" : methodName);
        }
        return task.source() + "|" + task.displayName();
    }

    private static String evidenceText(List<AdapterEvidence> evidence) {
        StringBuilder builder = new StringBuilder(" Adapter evidence:");
        for (AdapterEvidence item : evidence) {
            builder.append(System.lineSeparator())
                    .append(" - ").append(item.adapterId())
                    .append(": available=").append(item.available())
                    .append(", discovered=").append(item.discoveredCount());
            if (item.error() != null) builder.append(", error=").append(item.error());
        }
        return builder.toString();
    }

    private static Map<String, String> systemProperties() {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        properties.remove(TargetClasspathDescriptor.SYSTEM_PROPERTY);
        return properties;
    }

    private static List<Path> currentClasspath() {
        String raw = System.getProperty("java.class.path", "");
        if (raw.isBlank()) throw new IllegalStateException("java.class.path is empty and no target classpath was supplied");
        return java.util.Arrays.stream(raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> value != null && !value.isBlank()).map(Path::of).toList();
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }

    public record AdapterEvidence(String adapterId, String framework, boolean available,
                                  int discoveredCount, String error) implements Serializable {}

    public record DiscoveryResult(List<String> adapters,
                                  List<AdapterEvidence> evidence,
                                  List<String> warnings,
                                  List<ScenarioTask> tasks) implements Serializable {
        public DiscoveryResult {
            adapters = List.copyOf(adapters == null ? List.of() : adapters);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
        }
    }

    private record Selection(List<String> adapterIds, List<ScenarioTask> tasks, List<String> warnings) {
        private Selection {
            adapterIds = List.copyOf(adapterIds);
            tasks = List.copyOf(tasks);
            warnings = List.copyOf(warnings);
        }
    }

    private static final class Arguments {
        private final Path output;
        private final List<Path> testRoots;
        private final String adapter;
        private final AdapterMismatchPolicy mismatchPolicy;
        private final List<String> includeClassNameRegexes;
        private final List<String> excludeClassNameRegexes;

        private Arguments(Path output,
                          List<Path> testRoots,
                          String adapter,
                          AdapterMismatchPolicy mismatchPolicy,
                          List<String> includeClassNameRegexes,
                          List<String> excludeClassNameRegexes) {
            this.output = output;
            this.testRoots = testRoots;
            this.adapter = adapter;
            this.mismatchPolicy = mismatchPolicy;
            this.includeClassNameRegexes = includeClassNameRegexes;
            this.excludeClassNameRegexes = excludeClassNameRegexes;
        }

        private static Arguments parse(String[] args) {
            Path output = null;
            List<Path> roots = new ArrayList<>();
            String adapter = ScenarioMeshConfig.AUTO_ADAPTER;
            AdapterMismatchPolicy mismatchPolicy = AdapterMismatchPolicy.FAIL;
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    case "--test-root" -> roots.add(Path.of(requireValue(args, ++i, "--test-root")));
                    case "--adapter" -> adapter = requireValue(args, ++i, "--adapter").trim().toLowerCase(java.util.Locale.ROOT);
                    case "--adapter-mismatch-policy" -> mismatchPolicy = AdapterMismatchPolicy.parse(requireValue(args, ++i, "--adapter-mismatch-policy"));
                    case "--include-class-regex" -> includes.add(requireValue(args, ++i, "--include-class-regex"));
                    case "--exclude-class-regex" -> excludes.add(requireValue(args, ++i, "--exclude-class-regex"));
                    default -> throw new IllegalArgumentException("Unknown discovery argument: " + args[i]);
                }
            }
            if (output == null) throw new IllegalArgumentException("--output is required");
            return new Arguments(output, List.copyOf(roots), adapter, mismatchPolicy,
                    List.copyOf(includes), List.copyOf(excludes));
        }

        private static String requireValue(String[] args, int index, String name) {
            if (index >= args.length) throw new IllegalArgumentException(name + " requires a value");
            return args[index];
        }
    }
}
