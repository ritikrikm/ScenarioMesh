package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Ports.AdapterContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        new FrameworkOwnershipGuard().verifyNoUnsupportedExecutableFamilies(context);
        ExecutionBackendInventory.Inventory inventory = ExecutionBackendInventory.inspect(
                loader, parsed.testRoots, parsed.includes, parsed.excludes);

        Files.createDirectories(parsed.output.getParent());
        ObjectMapper mapper = JsonCodec.create();
        mapper.writerWithDefaultPrettyPrinter().writeValue(parsed.output.toFile(),
                new ProbeResult(inventory.ownership().name(), inventory.summary()));
    }

    public record ProbeResult(String ownership, String summary) {}

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
