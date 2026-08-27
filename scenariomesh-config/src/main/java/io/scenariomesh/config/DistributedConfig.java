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
        Duration registrationTimeout,
        TlsConfig tls) {

    /** Backward-compatible constructor for callers compiled before TLS configuration existed. */
    public DistributedConfig(WorkerMode mode, String bindHost, int bindPort, String token,
                             Duration registrationTimeout) {
        this(mode, bindHost, bindPort, token, registrationTimeout, TlsConfig.disabled());
    }

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
        tls = tls == null ? TlsConfig.disabled() : tls;
        if (mode == WorkerMode.REMOTE) {
            if (bindPort == 0) {
                throw new IllegalArgumentException("Invalid configuration: distributed.bindPort must be explicit in remote mode");
            }
            if (token.isBlank()) {
                throw new IllegalArgumentException("Invalid configuration: distributed.token is required in remote mode");
            }
            if (!isLoopbackHost(bindHost) && !tls.enabled()) {
                throw new IllegalArgumentException("Invalid configuration: non-loopback distributed.bindHost requires distributed.tls.enabled=true");
            }
        }
    }

    public static DistributedConfig defaults() {
        return new DistributedConfig(WorkerMode.LOCAL, "127.0.0.1", 0, "", Duration.ofSeconds(30), TlsConfig.disabled());
    }

    public boolean remote() {
        return mode == WorkerMode.REMOTE;
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1") || normalized.equals("localhost") || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1");
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
