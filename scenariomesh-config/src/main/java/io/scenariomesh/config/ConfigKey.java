package io.scenariomesh.config;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for external ScenarioMesh configuration names.
 *
 * <p>Keeping canonical names and backward-compatible aliases here prevents
 * Maven integration, YAML parsing and environment handling from drifting apart.</p>
 */
enum ConfigKey {
    CONFIG_FILE("scenariomesh.config.file"),
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

    private final String canonical;
    private final List<String> aliases;

    ConfigKey(String canonical, String... aliases) {
        this.canonical = canonical;
        this.aliases = List.of(aliases);
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

    private static String environmentName(String propertyName) {
        String snakeCase = propertyName.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return snakeCase.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
