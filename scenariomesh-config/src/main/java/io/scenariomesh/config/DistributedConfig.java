package io.scenariomesh.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Immutable coordinator/remote-worker transport settings. */
public record DistributedConfig(
        WorkerMode mode,
        String bindHost,
        int bindPort,
        String token,
        Duration registrationTimeout) {

    public DistributedConfig {
        mode = Objects.requireNonNull(mode, "mode");
        bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost.trim();
        if (bindPort < 0 || bindPort > 65535) {
            throw new IllegalArgumentException("Invalid configuration: distributed.bindPort must be between 0 and 65535");
        }
        registrationTimeout = Objects.requireNonNull(registrationTimeout, "registrationTimeout");
        if (registrationTimeout.isZero() || registrationTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid configuration: distributed.registrationTimeout must be greater than 0");
        }
        token = token == null ? "" : token.trim();
        if (mode == WorkerMode.REMOTE) {
            if (bindPort == 0) {
                throw new IllegalArgumentException("Invalid configuration: distributed.bindPort must be explicit in remote mode");
            }
            if (token.isBlank()) {
                throw new IllegalArgumentException("Invalid configuration: distributed.token is required in remote mode");
            }
        }
    }

    public static DistributedConfig defaults() {
        return new DistributedConfig(WorkerMode.LOCAL, "127.0.0.1", 0, "", Duration.ofSeconds(30));
    }

    public boolean remote() {
        return mode == WorkerMode.REMOTE;
    }

    public enum WorkerMode {
        LOCAL("local"),
        REMOTE("remote");

        private final String externalValue;

        WorkerMode(String externalValue) {
            this.externalValue = externalValue;
        }

        public String externalValue() {
            return externalValue;
        }

        public static WorkerMode parse(String value) {
            if (value == null || value.isBlank()) return LOCAL;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (WorkerMode mode : values()) {
                if (mode.externalValue.equals(normalized)) return mode;
            }
            throw new IllegalArgumentException("Invalid configuration: workers.mode must be one of: local, remote");
        }
    }
}
