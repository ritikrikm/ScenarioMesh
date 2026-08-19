package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.config.ScenarioMeshConfig.AdapterMismatchPolicy;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;

import java.nio.file.Files;
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
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        AdapterRegistry registry = new AdapterRegistry();
        Map<String, String> properties = systemProperties();
        AdapterContext context = new AdapterContext(classLoader, parsed.testRoots, properties);

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
                List<ScenarioTask> discovered = adapter.discover(context);
                discoveredByAdapter.put(adapter.id(), List.copyOf(discovered));
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), true, discovered.size(), null));
            } catch (Exception | LinkageError exception) {
                evidence.add(new AdapterEvidence(adapter.id(), adapter.framework(), true, 0,
                        "discovery failed: " + message(exception)));
                autoDiscoveryErrors.add(adapter.id() + ": discovery failed: " + message(exception));
            }
        }

        Selection selection = select(parsed, registry, discoveredByAdapter, evidence, autoDiscoveryErrors);
        Files.createDirectories(parsed.output.getParent());
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                parsed.output.toFile(),
                new DiscoveryResult(
                        List.of(selection.adapterId()),
                        List.copyOf(evidence),
                        selection.warnings(),
                        selection.tasks()));
    }

    private static Selection select(
            Arguments parsed,
            AdapterRegistry registry,
            Map<String, List<ScenarioTask>> discoveredByAdapter,
            List<AdapterEvidence> evidence,
            List<String> autoDiscoveryErrors) {
        Map<String, List<ScenarioTask>> candidates = new LinkedHashMap<>();
        discoveredByAdapter.forEach((id, tasks) -> {
            if (!tasks.isEmpty()) {
                candidates.put(id, tasks);
            }
        });

        if (ScenarioMeshConfig.AUTO_ADAPTER.equals(parsed.adapter)) {
            if (!autoDiscoveryErrors.isEmpty()) {
                throw new IllegalStateException("ScenarioMesh auto-detection could not safely evaluate every available adapter. "
                        + String.join("; ", autoDiscoveryErrors) + evidenceText(evidence));
            }
            if (candidates.isEmpty()) {
                throw new IllegalStateException("ScenarioMesh detected no adapter with executable tests." + evidenceText(evidence));
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException("ScenarioMesh adapter ownership is ambiguous: "
                        + String.join(", ", candidates.keySet())
                        + " all discovered executable tests. Configure scenariomesh.execution.adapter explicitly "
                        + "or use a more specific supported adapter." + evidenceText(evidence));
            }
            Map.Entry<String, List<ScenarioTask>> selected = candidates.entrySet().iterator().next();
            return new Selection(selected.getKey(), selected.getValue(), List.of());
        }

        // Validate that the configured id belongs to this installed runtime before
        // treating it as a repository assertion.
        registry.required(parsed.adapter);
        List<ScenarioTask> configuredTasks = discoveredByAdapter.get(parsed.adapter);
        if (configuredTasks != null && !configuredTasks.isEmpty()) {
            List<String> warnings = candidates.size() > 1
                    ? List.of("Multiple adapters discovered executable tests (" + String.join(", ", candidates.keySet())
                    + "); explicit configuration selected '" + parsed.adapter + "'.")
                    : List.of();
            return new Selection(parsed.adapter, configuredTasks, warnings);
        }

        String mismatch = "Configured adapter '" + parsed.adapter
                + "' did not discover executable tests." + evidenceText(evidence);
        if (parsed.mismatchPolicy == AdapterMismatchPolicy.USE_DETECTED
                && candidates.size() == 1
                && autoDiscoveryErrors.isEmpty()) {
            Map.Entry<String, List<ScenarioTask>> detected = candidates.entrySet().iterator().next();
            return new Selection(
                    detected.getKey(),
                    detected.getValue(),
                    List.of(mismatch + " Using uniquely detected adapter '" + detected.getKey()
                            + "' because execution.adapterMismatchPolicy=use-detected."));
        }
        throw new IllegalStateException(mismatch + " Policy is " + parsed.mismatchPolicy.externalValue()
                + "; ScenarioMesh will not guess which tests to run.");
    }

    private static String evidenceText(List<AdapterEvidence> evidence) {
        StringBuilder builder = new StringBuilder(" Adapter evidence:");
        for (AdapterEvidence item : evidence) {
            builder.append(System.lineSeparator())
                    .append(" - ").append(item.adapterId())
                    .append(": available=").append(item.available())
                    .append(", discovered=").append(item.discoveredCount());
            if (item.error() != null) {
                builder.append(", error=").append(item.error());
            }
        }
        return builder.toString();
    }

    private static Map<String, String> systemProperties() {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        return properties;
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getName() : message;
    }

    public record AdapterEvidence(
            String adapterId,
            String framework,
            boolean available,
            int discoveredCount,
            String error) {}

    public record DiscoveryResult(
            List<String> adapters,
            List<AdapterEvidence> evidence,
            List<String> warnings,
            List<ScenarioTask> tasks) {
        public DiscoveryResult {
            adapters = List.copyOf(adapters == null ? List.of() : adapters);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            tasks = List.copyOf(tasks == null ? List.of() : tasks);
        }
    }

    private record Selection(String adapterId, List<ScenarioTask> tasks, List<String> warnings) {
        private Selection {
            tasks = List.copyOf(tasks);
            warnings = List.copyOf(warnings);
        }
    }

    private static final class Arguments {
        private final Path output;
        private final List<Path> testRoots;
        private final String adapter;
        private final AdapterMismatchPolicy mismatchPolicy;

        private Arguments(Path output, List<Path> testRoots, String adapter, AdapterMismatchPolicy mismatchPolicy) {
            this.output = output;
            this.testRoots = testRoots;
            this.adapter = adapter;
            this.mismatchPolicy = mismatchPolicy;
        }

        private static Arguments parse(String[] args) {
            Path output = null;
            List<Path> roots = new ArrayList<>();
            String adapter = ScenarioMeshConfig.AUTO_ADAPTER;
            AdapterMismatchPolicy mismatchPolicy = AdapterMismatchPolicy.FAIL;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    case "--test-root" -> roots.add(Path.of(requireValue(args, ++i, "--test-root")));
                    case "--adapter" -> adapter = requireValue(args, ++i, "--adapter").trim().toLowerCase(java.util.Locale.ROOT);
                    case "--adapter-mismatch-policy" -> mismatchPolicy = AdapterMismatchPolicy.parse(
                            requireValue(args, ++i, "--adapter-mismatch-policy"));
                    default -> throw new IllegalArgumentException("Unknown discovery argument: " + args[i]);
                }
            }
            if (output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            return new Arguments(output, List.copyOf(roots), adapter, mismatchPolicy);
        }

        private static String requireValue(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }
    }
}
