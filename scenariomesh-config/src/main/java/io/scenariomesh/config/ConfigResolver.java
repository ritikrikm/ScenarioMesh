package io.scenariomesh.config;

import io.scenariomesh.config.ConfigFileLoader.LoadedConfig;
import io.scenariomesh.config.DistributedConfig.WorkerMode;
import io.scenariomesh.config.ScenarioMeshConfig.AdapterMismatchPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves all ScenarioMesh configuration through one precedence path. */
public final class ConfigResolver {
    private final ConfigFileLoader fileLoader = new ConfigFileLoader();

    public ScenarioMeshConfig resolve(Path projectDirectory, Path buildDirectory,
                                      Map<String, String> properties, Map<String, String> environment) {
        return resolveDetailed(projectDirectory, buildDirectory, properties, environment).config();
    }

    public ConfigResolution resolveDetailed(Path projectDirectory, Path buildDirectory,
                                            Map<String, String> properties, Map<String, String> environment) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(buildDirectory, "buildDirectory");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        environment = environment == null ? Map.of() : Map.copyOf(environment);

        Path explicitFile = optionalPath(rawExternal(ConfigKey.CONFIG_FILE, properties, environment), projectDirectory);
        LoadedConfig loaded = fileLoader.load(projectDirectory, explicitFile);
        Map<String, Object> yaml = loaded.values();
        ScenarioMeshConfig defaults = ScenarioMeshConfig.defaults(buildDirectory);

        int workerCount = intValue(value(ConfigKey.WORKER_COUNT, properties, environment, yaml),
                defaults.workerCount(), ConfigKey.WORKER_COUNT);
        Object minimumReadyRaw = value(ConfigKey.WORKER_MINIMUM_READY, properties, environment, yaml);
        int minimumReady = minimumReadyRaw == null
                ? workerCount : intValue(minimumReadyRaw, workerCount, ConfigKey.WORKER_MINIMUM_READY);

        DistributedConfig distributedDefaults = defaults.distributed();
        WorkerMode workerMode = workerMode(
                value(ConfigKey.WORKER_MODE, properties, environment, yaml), distributedDefaults.mode());
        TlsConfig tls = new TlsConfig(
                booleanValue(value(ConfigKey.DISTRIBUTED_TLS_ENABLED, properties, environment, yaml),
                        distributedDefaults.tls().enabled(), ConfigKey.DISTRIBUTED_TLS_ENABLED),
                booleanValue(value(ConfigKey.DISTRIBUTED_TLS_REQUIRE_CLIENT_AUTH, properties, environment, yaml),
                        distributedDefaults.tls().requireClientAuth(), ConfigKey.DISTRIBUTED_TLS_REQUIRE_CLIENT_AUTH),
                optionalConfiguredPath(value(ConfigKey.DISTRIBUTED_TLS_KEY_STORE, properties, environment, yaml), projectDirectory),
                stringValue(value(ConfigKey.DISTRIBUTED_TLS_KEY_STORE_PASSWORD, properties, environment, yaml),
                        distributedDefaults.tls().keyStorePassword()),
                optionalConfiguredPath(value(ConfigKey.DISTRIBUTED_TLS_TRUST_STORE, properties, environment, yaml), projectDirectory),
                stringValue(value(ConfigKey.DISTRIBUTED_TLS_TRUST_STORE_PASSWORD, properties, environment, yaml),
                        distributedDefaults.tls().trustStorePassword()));
        DistributedConfig distributed = new DistributedConfig(
                workerMode,
                stringValue(value(ConfigKey.DISTRIBUTED_BIND_HOST, properties, environment, yaml), distributedDefaults.bindHost()),
                intValue(value(ConfigKey.DISTRIBUTED_BIND_PORT, properties, environment, yaml),
                        distributedDefaults.bindPort(), ConfigKey.DISTRIBUTED_BIND_PORT),
                stringValue(value(ConfigKey.DISTRIBUTED_TOKEN, properties, environment, yaml), distributedDefaults.token()),
                durationValue(value(ConfigKey.DISTRIBUTED_REGISTRATION_TIMEOUT, properties, environment, yaml),
                        distributedDefaults.registrationTimeout(), ConfigKey.DISTRIBUTED_REGISTRATION_TIMEOUT),
                tls);

        ScenarioMeshConfig resolved = new ScenarioMeshConfig(
                booleanValue(value(ConfigKey.ENABLED, properties, environment, yaml), defaults.enabled(), ConfigKey.ENABLED),
                stringValue(value(ConfigKey.EXECUTION_ADAPTER, properties, environment, yaml), defaults.executionAdapter()),
                mismatchPolicy(value(ConfigKey.ADAPTER_MISMATCH_POLICY, properties, environment, yaml), defaults.adapterMismatchPolicy()),
                intValue(value(ConfigKey.INFRASTRUCTURE_RETRIES, properties, environment, yaml), defaults.infrastructureRetries(), ConfigKey.INFRASTRUCTURE_RETRIES),
                workerCount, minimumReady,
                intValue(value(ConfigKey.WORKER_MAX_TASKS, properties, environment, yaml), defaults.maxTasksPerWorker(), ConfigKey.WORKER_MAX_TASKS),
                intValue(value(ConfigKey.WORKER_MAX_HEAP_PERCENT, properties, environment, yaml), defaults.maxHeapUsagePercent(), ConfigKey.WORKER_MAX_HEAP_PERCENT),
                durationValue(value(ConfigKey.DISCOVERY_TIMEOUT, properties, environment, yaml), defaults.discoveryTimeout(), ConfigKey.DISCOVERY_TIMEOUT),
                durationValue(value(ConfigKey.WORKER_STARTUP_TIMEOUT, properties, environment, yaml), defaults.workerStartupTimeout(), ConfigKey.WORKER_STARTUP_TIMEOUT),
                durationValue(value(ConfigKey.WORKER_TASK_TIMEOUT, properties, environment, yaml), defaults.workerTaskTimeout(), ConfigKey.WORKER_TASK_TIMEOUT),
                durationValue(value(ConfigKey.WORKER_SHUTDOWN_TIMEOUT, properties, environment, yaml), defaults.workerShutdownTimeout(), ConfigKey.WORKER_SHUTDOWN_TIMEOUT),
                pathValue(value(ConfigKey.REPORTING_DIRECTORY, properties, environment, yaml), projectDirectory, defaults.reportingDirectory()),
                listValue(value(ConfigKey.WORKER_JVM_ARGS, properties, environment, yaml), defaults.workerJvmArgs()),
                booleanValue(value(ConfigKey.LOGGING_LIVE_CONSOLE, properties, environment, yaml), defaults.liveConsoleLogs(), ConfigKey.LOGGING_LIVE_CONSOLE),
                booleanValue(value(ConfigKey.LOGGING_WORKER_FILES, properties, environment, yaml), defaults.workerLogFiles(), ConfigKey.LOGGING_WORKER_FILES),
                booleanValue(value(ConfigKey.LOGGING_SHOW_CONFIGURATION, properties, environment, yaml), defaults.showConfiguration(), ConfigKey.LOGGING_SHOW_CONFIGURATION),
                booleanValue(value(ConfigKey.LOGGING_SHOW_PROGRESS, properties, environment, yaml), defaults.showProgress(), ConfigKey.LOGGING_SHOW_PROGRESS),
                distributed);
        return new ConfigResolution(resolved, loaded.source());
    }

    private Object value(ConfigKey key, Map<String, String> properties,
                         Map<String, String> environment, Map<String, Object> yaml) {
        String external = rawExternal(key, properties, environment);
        return external != null ? external : yaml.get(yamlPath(key));
    }

    private String rawExternal(ConfigKey key, Map<String, String> properties, Map<String, String> environment) {
        for (String name : key.propertyNames()) { String value = properties.get(name); if (value != null) return value; }
        for (String name : key.environmentNames()) { String value = environment.get(name); if (value != null) return value; }
        return null;
    }

    private String yamlPath(ConfigKey key) {
        String canonical = key.canonical();
        String prefix = "scenariomesh.";
        return canonical.startsWith(prefix) ? canonical.substring(prefix.length()) : canonical;
    }

    private boolean booleanValue(Object raw, boolean defaultValue, ConfigKey key) {
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean value) return value;
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw invalid(key, "must be true or false");
    }

    private int intValue(Object raw, int defaultValue, ConfigKey key) {
        if (raw == null) return defaultValue;
        if (raw instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(raw).trim()); }
        catch (NumberFormatException exception) { throw invalid(key, "must be an integer"); }
    }

    private Duration durationValue(Object raw, Duration defaultValue, ConfigKey key) {
        if (raw == null) return defaultValue;
        try { return Duration.parse(String.valueOf(raw).trim()); }
        catch (DateTimeParseException exception) { throw invalid(key, "must be an ISO-8601 duration such as PT30S or PT2M"); }
    }

    private String stringValue(Object raw, String defaultValue) { return raw == null ? defaultValue : String.valueOf(raw).trim(); }
    private AdapterMismatchPolicy mismatchPolicy(Object raw, AdapterMismatchPolicy defaultValue) { return raw == null ? defaultValue : AdapterMismatchPolicy.parse(String.valueOf(raw).trim()); }
    private WorkerMode workerMode(Object raw, WorkerMode defaultValue) { return raw == null ? defaultValue : WorkerMode.parse(String.valueOf(raw).trim()); }

    private Path pathValue(Object raw, Path projectDirectory, Path defaultValue) {
        if (raw == null) return defaultValue.toAbsolutePath().normalize();
        Path path = Path.of(String.valueOf(raw).trim());
        return (path.isAbsolute() ? path : projectDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private Path optionalConfiguredPath(Object raw, Path projectDirectory) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        Path path = Path.of(String.valueOf(raw).trim());
        return (path.isAbsolute() ? path : projectDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private Path optionalPath(String raw, Path projectDirectory) {
        if (raw == null || raw.isBlank()) return null;
        Path path = Path.of(raw.trim());
        return (path.isAbsolute() ? path : projectDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    private List<String> listValue(Object raw, List<String> defaultValue) {
        if (raw == null) return defaultValue;
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (item == null || String.valueOf(item).isBlank()) {
                    throw new IllegalArgumentException("Invalid configuration: workers.jvmArgs cannot contain blank values");
                }
                values.add(String.valueOf(item));
            }
            return List.copyOf(values);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return List.of();
        return Arrays.stream(text.split("\\s+")).filter(value -> !value.isBlank()).toList();
    }

    private IllegalArgumentException invalid(ConfigKey key, String reason) {
        return new IllegalArgumentException("Invalid configuration: " + key.canonical() + " " + reason);
    }

    public record ConfigResolution(ScenarioMeshConfig config, Optional<Path> configFile) {
        public ConfigResolution {
            Objects.requireNonNull(config, "config");
            configFile = configFile == null ? Optional.empty() : configFile;
        }
    }
}
