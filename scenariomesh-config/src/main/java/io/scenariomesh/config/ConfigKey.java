package io.scenariomesh.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for external ScenarioMesh configuration names.
 *
 * <p>Canonical property names, legacy aliases, environment names and YAML paths are all derived
 * from this registry so Maven, CLI, worker and file parsing cannot evolve different schemas.</p>
 */
enum ConfigKey {
    CONFIG_FILE("scenariomesh.config.file", false),
    ENABLED("scenariomesh.enabled"),
    EXECUTION_ADAPTER("scenariomesh.execution.adapter"),
    ADAPTER_MISMATCH_POLICY("scenariomesh.execution.adapterMismatchPolicy"),
    INFRASTRUCTURE_RETRIES("scenariomesh.execution.infrastructureRetries"),
    SCHEDULING_STRATEGY("scenariomesh.scheduling.strategy"),
    WORKER_COUNT("scenariomesh.workers.count", "scenariomesh.workers"),
    WORKER_MINIMUM_READY("scenariomesh.workers.minimumReady"),
    WORKER_MODE("scenariomesh.workers.mode"),
    WORKER_MAX_TASKS("scenariomesh.workers.maxTasksPerWorker"),
    WORKER_MAX_HEAP_PERCENT("scenariomesh.workers.maxHeapUsagePercent"),
    WORKER_STARTUP_TIMEOUT("scenariomesh.workers.startupTimeout", "scenariomesh.worker.startupTimeout"),
    WORKER_TASK_TIMEOUT("scenariomesh.workers.taskTimeout", "scenariomesh.worker.taskTimeout"),
    WORKER_SHUTDOWN_TIMEOUT("scenariomesh.workers.shutdownTimeout", "scenariomesh.worker.shutdownTimeout"),
    WORKER_JVM_ARGS("scenariomesh.workers.jvmArgs", "scenariomesh.worker.jvmArgs"),
    DISTRIBUTED_BIND_HOST("scenariomesh.distributed.bindHost"),
    DISTRIBUTED_BIND_PORT("scenariomesh.distributed.bindPort"),
    DISTRIBUTED_TOKEN("scenariomesh.distributed.token"),
    DISTRIBUTED_REGISTRATION_TIMEOUT("scenariomesh.distributed.registrationTimeout"),
    DISTRIBUTED_TLS_ENABLED("scenariomesh.distributed.tls.enabled"),
    DISTRIBUTED_TLS_REQUIRE_CLIENT_AUTH("scenariomesh.distributed.tls.requireClientAuth"),
    DISTRIBUTED_TLS_KEY_STORE("scenariomesh.distributed.tls.keyStore"),
    DISTRIBUTED_TLS_KEY_STORE_PASSWORD("scenariomesh.distributed.tls.keyStorePassword"),
    DISTRIBUTED_TLS_TRUST_STORE("scenariomesh.distributed.tls.trustStore"),
    DISTRIBUTED_TLS_TRUST_STORE_PASSWORD("scenariomesh.distributed.tls.trustStorePassword"),
    DISCOVERY_TIMEOUT("scenariomesh.discovery.timeout"),
    REPORTING_DIRECTORY("scenariomesh.reporting.directory"),
    LOGGING_LIVE_CONSOLE("scenariomesh.logging.liveConsole"),
    LOGGING_WORKER_FILES("scenariomesh.logging.workerFiles"),
    LOGGING_SHOW_CONFIGURATION("scenariomesh.logging.showConfiguration"),
    LOGGING_SHOW_PROGRESS("scenariomesh.logging.showProgress");

    private static final String PREFIX = "scenariomesh.";
    private final String canonical;
    private final List<String> aliases;
    private final boolean yamlVisible;

    ConfigKey(String canonical, String... aliases) {
        this(canonical, true, aliases);
    }

    ConfigKey(String canonical, boolean yamlVisible, String... aliases) {
        if (canonical == null || !canonical.startsWith(PREFIX)) {
            throw new IllegalArgumentException("ScenarioMesh config key must use the 'scenariomesh.' namespace");
        }
        this.canonical = canonical;
        this.aliases = List.of(aliases);
        this.yamlVisible = yamlVisible;
    }

    String canonical() { return canonical; }

    List<String> propertyNames() {
        if (aliases.isEmpty()) return List.of(canonical);
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        names.add(canonical);
        names.addAll(aliases);
        return List.copyOf(names);
    }

    List<String> environmentNames() {
        return propertyNames().stream().map(ConfigKey::environmentName).toList();
    }

    Optional<String> yamlPath() {
        return yamlVisible ? Optional.of(canonical.substring(PREFIX.length())) : Optional.empty();
    }

    static Set<String> yamlPaths() {
        return java.util.Arrays.stream(values())
                .map(ConfigKey::yamlPath)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String environmentName(String propertyName) {
        String snakeCase = propertyName.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return snakeCase.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
