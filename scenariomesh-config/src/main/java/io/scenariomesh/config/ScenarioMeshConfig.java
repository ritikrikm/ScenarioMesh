package io.scenariomesh.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable, fully resolved ScenarioMesh runtime configuration. */
public record ScenarioMeshConfig(
        boolean enabled,
        String executionAdapter,
        AdapterMismatchPolicy adapterMismatchPolicy,
        int workerCount,
        Duration discoveryTimeout,
        Duration workerStartupTimeout,
        Duration workerShutdownTimeout,
        Path reportingDirectory,
        List<String> workerJvmArgs,
        boolean liveConsoleLogs,
        boolean workerLogFiles,
        boolean showConfiguration,
        boolean showProgress) {

    public static final String AUTO_ADAPTER = "auto";

    public ScenarioMeshConfig {
        executionAdapter = normalizeAdapter(executionAdapter);
        Objects.requireNonNull(adapterMismatchPolicy, "adapterMismatchPolicy");
        if (workerCount < 1) {
            throw new IllegalArgumentException("Invalid configuration: workers.count must be greater than 0");
        }
        requirePositive(discoveryTimeout, "discovery.timeout");
        requirePositive(workerStartupTimeout, "workers.startupTimeout");
        requirePositive(workerShutdownTimeout, "workers.shutdownTimeout");
        if (reportingDirectory == null) {
            throw new IllegalArgumentException("Invalid configuration: reporting.directory is required");
        }
        workerJvmArgs = List.copyOf(workerJvmArgs == null ? List.of() : workerJvmArgs);
    }

    public boolean automaticAdapterSelection() {
        return AUTO_ADAPTER.equals(executionAdapter);
    }

    public static ScenarioMeshConfig defaults(Path buildDirectory) {
        return new ScenarioMeshConfig(
                true,
                AUTO_ADAPTER,
                AdapterMismatchPolicy.FAIL,
                4,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                buildDirectory.resolve("scenariomesh"),
                List.of(),
                true,
                true,
                true,
                true);
    }

    private static String normalizeAdapter(String adapter) {
        if (adapter == null || adapter.isBlank()) {
            throw new IllegalArgumentException("Invalid configuration: execution.adapter must not be blank");
        }
        return adapter.trim().toLowerCase(Locale.ROOT);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("Invalid configuration: " + name + " must be greater than 0");
        }
    }

    public enum AdapterMismatchPolicy {
        FAIL("fail"),
        USE_DETECTED("use-detected");

        private final String externalValue;

        AdapterMismatchPolicy(String externalValue) {
            this.externalValue = externalValue;
        }

        public String externalValue() {
            return externalValue;
        }

        public static AdapterMismatchPolicy parse(String value) {
            for (AdapterMismatchPolicy policy : values()) {
                if (policy.externalValue.equalsIgnoreCase(value)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Invalid configuration: execution.adapterMismatchPolicy must be one of: fail, use-detected");
        }
    }
}
