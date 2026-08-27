package io.scenariomesh.workerruntime;

import io.scenariomesh.config.TlsConfig;
import io.scenariomesh.config.TlsContextFactory;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;

/** Opens worker-to-coordinator sockets with strict TLS hostname verification when configured. */
final class RemoteWorkerTransport {
    private RemoteWorkerTransport() {}

    static Socket connect(String host, int port, Map<String, String> environment) throws Exception {
        boolean tls = Boolean.parseBoolean(environment.getOrDefault("SCENARIOMESH_REMOTE_TLS_ENABLED", "false"));
        if (!tls) {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("Non-loopback ScenarioMesh remote workers require TLS");
            }
            return new Socket(InetAddress.getByName(host), port);
        }
        TlsConfig config = new TlsConfig(
                true,
                true,
                requiredPath(environment, "SCENARIOMESH_REMOTE_TLS_KEY_STORE"),
                required(environment, "SCENARIOMESH_REMOTE_TLS_KEY_STORE_PASSWORD"),
                requiredPath(environment, "SCENARIOMESH_REMOTE_TLS_TRUST_STORE"),
                required(environment, "SCENARIOMESH_REMOTE_TLS_TRUST_STORE_PASSWORD"));
        SSLSocket socket = (SSLSocket) TlsContextFactory.create(config).getSocketFactory().createSocket(host, port);
        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    static String authenticationToken(Map<String, String> environment) {
        return required(environment, "SCENARIOMESH_REMOTE_TOKEN");
    }

    private static Path requiredPath(Map<String, String> environment, String name) {
        return Path.of(required(environment, name)).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
