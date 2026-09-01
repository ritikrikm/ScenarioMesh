package io.scenariomesh.workerruntime;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fingerprints process state observable by target tests and therefore relevant to transparent
 * Maven fork equivalence. The fingerprint is intentionally strict: false negatives keep Maven
 * native, while a false positive could execute a different test environment remotely.
 */
final class RuntimeEquivalenceFingerprint {
    private static final Set<String> IGNORED_SYSTEM_PROPERTIES = Set.of(
            TargetClasspathDescriptor.SYSTEM_PROPERTY,
            "sun.java.command", "sun.java.launcher", "jdk.module.main", "jdk.module.main.class");
    private static final Set<String> IGNORED_ENVIRONMENT = Set.of(
            "SCENARIOMESH_REMOTE_TOKEN",
            "SCENARIOMESH_REMOTE_TLS_ENABLED",
            "SCENARIOMESH_REMOTE_TLS_KEY_STORE",
            "SCENARIOMESH_REMOTE_TLS_KEY_STORE_PASSWORD",
            "SCENARIOMESH_REMOTE_TLS_TRUST_STORE",
            "SCENARIOMESH_REMOTE_TLS_TRUST_STORE_PASSWORD");

    private RuntimeEquivalenceFingerprint() {}

    static String capture(ClassLoader classLoader) throws Exception {
        StringBuilder input = new StringBuilder();
        append(input, "java.feature", Integer.toString(Runtime.version().feature()));
        append(input, "java.vendor", System.getProperty("java.vendor", "unknown"));
        append(input, "java.version", System.getProperty("java.version", "unknown"));
        append(input, "os.name", System.getProperty("os.name", "unknown"));
        append(input, "os.arch", System.getProperty("os.arch", "unknown"));
        append(input, "target.modulePath", System.getProperty(TargetRuntimeClassLoader.MODULE_PATH_PROPERTY, "false"));
        append(input, "target.classpath", System.getProperty(TargetClasspathDescriptor.SYSTEM_PROPERTY, ""));
        append(input, "realm", realm(classLoader));

        TreeMap<String, String> properties = new TreeMap<>();
        System.getProperties().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!IGNORED_SYSTEM_PROPERTIES.contains(name)) properties.put(name, String.valueOf(value));
        });
        properties.forEach((key, value) -> append(input, "sys." + key, value));

        TreeMap<String, String> environment = new TreeMap<>(System.getenv());
        IGNORED_ENVIRONMENT.forEach(environment::remove);
        environment.forEach((key, value) -> append(input, "env." + key, value));

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String realm(ClassLoader classLoader) {
        if (classLoader instanceof URLClassLoader urls) {
            return java.util.Arrays.stream(urls.getURLs()).map(Object::toString).sorted()
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return classLoader == null ? "bootstrap" : classLoader.getClass().getName();
    }

    private static void append(StringBuilder target, String key, String value) {
        target.append(key).append('=').append(value == null ? "" : value).append('\n');
    }
}
