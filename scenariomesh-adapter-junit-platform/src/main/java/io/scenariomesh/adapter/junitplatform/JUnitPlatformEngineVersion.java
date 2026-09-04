package io.scenariomesh.adapter.junitplatform;

import org.junit.platform.engine.TestEngine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Resolves version evidence from the exact artifact that supplied a loaded JUnit Platform engine. */
final class JUnitPlatformEngineVersion {
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?$");

    private static final Map<String, Coordinates> ENGINE_COORDINATES = Map.of(
            "junit-jupiter", new Coordinates("org.junit.jupiter", "junit-jupiter-engine"),
            "junit-vintage", new Coordinates("org.junit.vintage", "junit-vintage-engine"),
            "cucumber", new Coordinates("io.cucumber", "cucumber-junit-platform-engine"),
            "junit-platform-suite", new Coordinates("org.junit.platform", "junit-platform-suite-engine"));

    private JUnitPlatformEngineVersion() {}

    static VersionEvidence resolve(TestEngine engine) {
        if (engine == null) return unknown("unknown");

        Class<?> implementation = engine.getClass();
        String reportedVersion = normalize(engine.getVersion().orElse(null));
        Coordinates coordinates = ENGINE_COORDINATES.get(engine.getId());

        if (coordinates != null) {
            Optional<String> artifactVersion = versionFromExactCodeSource(implementation, coordinates);
            if (artifactVersion.isPresent()) {
                return new VersionEvidence(artifactVersion.get(), "maven-pom-properties", reportedVersion);
            }
        }

        String moduleVersion = moduleVersion(implementation);
        if (isSemanticVersion(moduleVersion)) {
            return new VersionEvidence(moduleVersion, "module-descriptor", reportedVersion);
        }

        Package implementationPackage = implementation.getPackage();
        if (implementationPackage != null) {
            String packageImplementationVersion = normalize(implementationPackage.getImplementationVersion());
            if (isSemanticVersion(packageImplementationVersion)) {
                return new VersionEvidence(packageImplementationVersion, "package-implementation-version", reportedVersion);
            }
            String packageSpecificationVersion = normalize(implementationPackage.getSpecificationVersion());
            if (isSemanticVersion(packageSpecificationVersion)) {
                return new VersionEvidence(packageSpecificationVersion, "package-specification-version", reportedVersion);
            }
        }

        if (isSemanticVersion(reportedVersion)) {
            return new VersionEvidence(reportedVersion, "test-engine-reported-version", reportedVersion);
        }

        return unknown(reportedVersion);
    }

    static boolean isSemanticVersion(String value) {
        String normalized = normalize(value);
        return !normalized.equals("unknown") && SEMANTIC_VERSION.matcher(normalized).matches();
    }

    private static Optional<String> versionFromExactCodeSource(Class<?> implementation, Coordinates coordinates) {
        try {
            if (implementation.getProtectionDomain() == null
                    || implementation.getProtectionDomain().getCodeSource() == null
                    || implementation.getProtectionDomain().getCodeSource().getLocation() == null) {
                return Optional.empty();
            }
            URL location = implementation.getProtectionDomain().getCodeSource().getLocation();
            if (!"file".equalsIgnoreCase(location.getProtocol())) return Optional.empty();

            Path codeSource = Path.of(location.toURI());
            String metadataPath = "META-INF/maven/" + coordinates.groupId() + "/"
                    + coordinates.artifactId() + "/pom.properties";

            if (Files.isDirectory(codeSource)) {
                Path propertiesFile = codeSource.resolve(metadataPath);
                if (!Files.isRegularFile(propertiesFile)) return Optional.empty();
                try (InputStream input = Files.newInputStream(propertiesFile)) {
                    return readVersion(input);
                }
            }

            if (!Files.isRegularFile(codeSource)) return Optional.empty();
            try (JarFile jar = new JarFile(codeSource.toFile())) {
                JarEntry entry = jar.getJarEntry(metadataPath);
                if (entry == null) return Optional.empty();
                try (InputStream input = jar.getInputStream(entry)) {
                    return readVersion(input);
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException ignored) {
            // Other exact-artifact metadata sources are attempted below. Never infer from a filename.
            return Optional.empty();
        }
    }

    private static Optional<String> readVersion(InputStream input) throws IOException {
        Properties properties = new Properties();
        properties.load(input);
        String version = normalize(properties.getProperty("version"));
        return isSemanticVersion(version) ? Optional.of(version) : Optional.empty();
    }

    private static String moduleVersion(Class<?> implementation) {
        try {
            if (implementation.getModule() == null || implementation.getModule().getDescriptor() == null) {
                return "unknown";
            }
            return implementation.getModule().getDescriptor().rawVersion().map(JUnitPlatformEngineVersion::normalize)
                    .orElse("unknown");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static VersionEvidence unknown(String reportedVersion) {
        return new VersionEvidence("unknown", "unresolved", normalize(reportedVersion));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim();
    }

    record VersionEvidence(String version, String source, String reportedVersion) {
        VersionEvidence {
            version = normalize(version);
            source = source == null || source.isBlank() ? "unknown" : source;
            reportedVersion = normalize(reportedVersion);
        }

        String diagnostic() {
            if (reportedVersion.equals(version) || "unknown".equals(reportedVersion)) {
                return version + " via " + source;
            }
            return version + " via " + source + " (engine reported " + reportedVersion + ")";
        }
    }

    private record Coordinates(String groupId, String artifactId) {}
}
