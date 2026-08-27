package io.scenariomesh.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads and validates the optional project-level ScenarioMesh YAML file. */
final class ConfigFileLoader {
    private static final String YAML = "scenariomesh.yml";
    private static final String YAML_LONG = "scenariomesh.yaml";
    private static final int SUPPORTED_VERSION = 1;

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "configVersion",
            "enabled",
            "execution.adapter",
            "execution.adapterMismatchPolicy",
            "execution.infrastructureRetries",
            "workers.count",
            "workers.minimumReady",
            "workers.mode",
            "workers.maxTasksPerWorker",
            "workers.maxHeapUsagePercent",
            "workers.startupTimeout",
            "workers.taskTimeout",
            "workers.shutdownTimeout",
            "workers.jvmArgs",
            "distributed.bindHost",
            "distributed.bindPort",
            "distributed.token",
            "distributed.registrationTimeout",
            "discovery.timeout",
            "reporting.directory",
            "logging.liveConsole",
            "logging.workerFiles",
            "logging.showConfiguration",
            "logging.showProgress"
    );

    LoadedConfig load(Path projectDirectory, Path explicitFile) {
        Path selected = selectFile(projectDirectory, explicitFile);
        if (selected == null) {
            return new LoadedConfig(Optional.empty(), Map.of());
        }

        Object document;
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        try (InputStream input = Files.newInputStream(selected)) {
            document = yaml.load(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read ScenarioMesh config file " + selected + ": "
                    + exception.getMessage(), exception);
        }

        if (!(document instanceof Map<?, ?> root)) {
            throw invalid(selected, "root document must be a YAML mapping");
        }
        if (root.size() != 1 || !root.containsKey("scenariomesh")) {
            throw invalid(selected, "root must contain only the 'scenariomesh' mapping");
        }
        Object scenarioMeshNode = root.get("scenariomesh");
        if (!(scenarioMeshNode instanceof Map<?, ?> scenarioMesh)) {
            throw invalid(selected, "'scenariomesh' must be a YAML mapping");
        }

        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", scenarioMesh, flattened, selected);
        validateVersion(flattened, selected);
        validateKnownKeys(flattened, selected);
        return new LoadedConfig(Optional.of(selected), Map.copyOf(flattened));
    }

    private Path selectFile(Path projectDirectory, Path explicitFile) {
        if (explicitFile != null) {
            Path normalized = explicitFile.isAbsolute()
                    ? explicitFile.toAbsolutePath().normalize()
                    : projectDirectory.resolve(explicitFile).toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IllegalArgumentException("ScenarioMesh config file does not exist: " + normalized);
            }
            return normalized;
        }

        Path shortName = projectDirectory.resolve(YAML).toAbsolutePath().normalize();
        Path longName = projectDirectory.resolve(YAML_LONG).toAbsolutePath().normalize();
        boolean hasShort = Files.isRegularFile(shortName);
        boolean hasLong = Files.isRegularFile(longName);
        if (hasShort && hasLong) {
            throw new IllegalArgumentException("Both " + YAML + " and " + YAML_LONG
                    + " exist. Keep one file or set -Dscenariomesh.config.file explicitly.");
        }
        if (hasShort) {
            return shortName;
        }
        return hasLong ? longName : null;
    }

    private void flatten(String prefix, Map<?, ?> source, Map<String, Object> target, Path file) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw invalid(file, "all configuration keys must be non-blank strings");
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(path, nested, target, file);
            } else {
                target.put(path, value);
            }
        }
    }

    private void validateVersion(Map<String, Object> flattened, Path file) {
        Object raw = flattened.get("configVersion");
        if (raw == null) {
            throw invalid(file, "'scenariomesh.configVersion' is required when a config file is present");
        }
        int version;
        if (raw instanceof Number number) {
            version = number.intValue();
        } else {
            try {
                version = Integer.parseInt(String.valueOf(raw));
            } catch (NumberFormatException exception) {
                throw invalid(file, "configVersion must be an integer");
            }
        }
        if (version != SUPPORTED_VERSION) {
            throw invalid(file, "unsupported configVersion " + version + "; this runtime supports "
                    + SUPPORTED_VERSION);
        }
    }

    private void validateKnownKeys(Map<String, Object> flattened, Path file) {
        Set<String> unknown = new LinkedHashSet<>(flattened.keySet());
        unknown.removeAll(ALLOWED_KEYS);
        if (!unknown.isEmpty()) {
            throw invalid(file, "unknown configuration key(s): " + String.join(", ", unknown));
        }
    }

    private IllegalArgumentException invalid(Path file, String reason) {
        return new IllegalArgumentException("Invalid ScenarioMesh configuration in " + file + ": " + reason);
    }

    record LoadedConfig(Optional<Path> source, Map<String, Object> values) {
        LoadedConfig {
            source = source == null ? Optional.empty() : source;
            values = Map.copyOf(values == null ? Map.of() : values);
        }
    }
}
