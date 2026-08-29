package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Canonical Maven-side property snapshot used by every ScenarioMesh Maven entry point.
 *
 * <p>Project properties are the base, Maven system properties override them, and Maven user
 * properties ({@code -D...}) win last. Keeping this in one place prevents preflight, run and
 * worker goals from proving/using different effective ScenarioMesh configuration.</p>
 */
final class EffectiveMavenProperties {
    private EffectiveMavenProperties() {}

    static Map<String, String> configuration(MavenProject project, MavenSession session) {
        Map<String, String> values = new LinkedHashMap<>();
        if (project != null) putAll(values, project.getProperties());
        if (session != null) {
            putAll(values, session.getSystemProperties());
            putAll(values, session.getUserProperties());
        }
        return Map.copyOf(values);
    }

    static Map<String, String> user(MavenSession session) {
        Map<String, String> values = new LinkedHashMap<>();
        if (session != null) putAll(values, session.getUserProperties());
        return Map.copyOf(values);
    }

    private static void putAll(Map<String, String> target, Properties source) {
        if (source == null) return;
        source.forEach((key, value) -> target.put(String.valueOf(key), String.valueOf(value)));
    }
}
