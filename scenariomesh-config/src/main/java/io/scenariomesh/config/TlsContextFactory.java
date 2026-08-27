package io.scenariomesh.config;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/** Creates JSSE contexts from explicitly configured PKCS12/JKS key and trust stores. */
public final class TlsContextFactory {
    private TlsContextFactory() {}

    public static SSLContext create(TlsConfig config) throws Exception {
        if (config == null || !config.enabled()) {
            throw new IllegalArgumentException("TLS context requires distributed.tls.enabled=true");
        }
        KeyStore keyStore = load(config.keyStore(), config.keyStorePassword());
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, config.keyStorePassword().toCharArray());

        KeyStore trustStore = load(config.trustStore(), config.trustStorePassword());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    private static KeyStore load(Path path, String password) throws Exception {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("TLS store does not exist or is not a regular file: " + path);
        }
        KeyStore store = KeyStore.getInstance(storeType(path));
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password.toCharArray());
        }
        return store;
    }

    private static String storeType(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12")) return "PKCS12";
        return KeyStore.getDefaultType();
    }
}
