package io.scenariomesh.cli;

import java.io.InputStream;
import java.util.Properties;

final class ScenarioMeshVersion {
    private static final String RESOURCE = "/scenariomesh-version.properties";

    private ScenarioMeshVersion() {}

    static String current() {
        try (InputStream input = ScenarioMeshVersion.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("ScenarioMesh version metadata is missing from the CLI artifact");
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("${")) {
                throw new IllegalStateException("ScenarioMesh version metadata is unresolved");
            }
            return version.trim();
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Unable to read ScenarioMesh version metadata: " + exception.getMessage(), exception);
        }
    }
}
