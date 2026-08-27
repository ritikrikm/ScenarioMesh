package io.scenariomesh.reporting;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/** Safe reference to an artifact produced by a test framework or reporting integration. */
public record ReportArtifact(
        String id,
        String scenarioId,
        String kind,
        String label,
        String location,
        String mediaType,
        Map<String, String> attributes) {

    public ReportArtifact {
        id = require(id, "id");
        kind = require(kind, "kind");
        label = require(label, "label");
        location = validateLocation(location);
        scenarioId = blankToNull(scenarioId);
        mediaType = blankToNull(mediaType);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        if (attributes.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("artifact attributes must not contain blank keys");
        }
    }

    private static String validateLocation(String value) {
        String location = require(value, "location");
        URI uri;
        try { uri = URI.create(location); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("artifact location must be a safe relative path or https URL", invalid); }
        if (uri.isAbsolute()) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("artifact absolute locations must use https");
            }
            return uri.normalize().toString();
        }
        Path path = Path.of(location).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) {
            throw new IllegalArgumentException("artifact local locations must stay relative to the reporting directory");
        }
        return path.toString().replace('\\', '/');
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("artifact " + name + " must not be blank");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
