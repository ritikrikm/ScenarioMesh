package io.scenariomesh.config;

import java.nio.file.Path;

/** Immutable TLS configuration for distributed coordinator/worker transport. */
public record TlsConfig(
        boolean enabled,
        boolean requireClientAuth,
        Path keyStore,
        String keyStorePassword,
        Path trustStore,
        String trustStorePassword) {

    public TlsConfig {
        keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
        trustStorePassword = trustStorePassword == null ? "" : trustStorePassword;
        if (enabled) {
            if (keyStore == null) {
                throw new IllegalArgumentException("Invalid configuration: distributed.tls.keyStore is required when TLS is enabled");
            }
            if (trustStore == null) {
                throw new IllegalArgumentException("Invalid configuration: distributed.tls.trustStore is required when TLS is enabled");
            }
            if (keyStorePassword.isBlank()) {
                throw new IllegalArgumentException("Invalid configuration: distributed.tls.keyStorePassword is required when TLS is enabled");
            }
            if (trustStorePassword.isBlank()) {
                throw new IllegalArgumentException("Invalid configuration: distributed.tls.trustStorePassword is required when TLS is enabled");
            }
        }
    }

    public static TlsConfig disabled() {
        return new TlsConfig(false, true, null, "", null, "");
    }

    /** Never expose keystore/truststore passwords through generated record diagnostics. */
    @Override
    public String toString() {
        return "TlsConfig[enabled=" + enabled
                + ", requireClientAuth=" + requireClientAuth
                + ", keyStore=" + keyStore
                + ", keyStorePassword=" + (keyStorePassword.isBlank() ? "<unset>" : "<redacted>")
                + ", trustStore=" + trustStore
                + ", trustStorePassword=" + (trustStorePassword.isBlank() ? "<unset>" : "<redacted>")
                + "]";
    }
}
