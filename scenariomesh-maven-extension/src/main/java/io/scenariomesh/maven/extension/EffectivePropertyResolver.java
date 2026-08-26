package io.scenariomesh.maven.extension;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.util.Objects;

/**
 * Resolves values from Maven's effective invocation/model without reparsing raw POM or settings files.
 *
 * <p>By the time a lifecycle participant evaluates a {@link MavenProject}, Maven has already applied
 * inheritance and active-profile model contributions. User properties (-D) remain the highest-priority
 * invocation values, followed by Maven system properties and the effective project's properties.</p>
 */
final class EffectivePropertyResolver {
    private final MavenSession session;
    private final MavenProject project;

    EffectivePropertyResolver(MavenSession session, MavenProject project) {
        this.session = Objects.requireNonNull(session, "session");
        this.project = Objects.requireNonNull(project, "project");
    }

    String resolve(String key) {
        String value = property(session.getUserProperties(), key);
        if (value != null) return value;

        value = property(session.getSystemProperties(), key);
        if (value != null) return value;

        value = property(project.getProperties(), key);
        if (value != null) return value;

        value = projectAlias(key);
        if (value != null) return value;

        if (key != null && key.startsWith("env.") && key.length() > 4) {
            value = System.getenv(key.substring(4));
            if (value != null) return value;
        }

        return key == null ? null : System.getProperty(key);
    }

    /**
     * Resolves Maven late-replacement values only from sources that cannot be mutated by an earlier
     * build plugin during this Maven invocation. Effective project properties are intentionally omitted.
     */
    String resolveStableLate(String key) {
        String value = property(session.getUserProperties(), key);
        if (value != null) return value;

        value = property(session.getSystemProperties(), key);
        if (value != null) return value;

        if (key != null && key.startsWith("env.") && key.length() > 4) {
            value = System.getenv(key.substring(4));
            if (value != null) return value;
        }

        return key == null ? null : System.getProperty(key);
    }

    boolean present(String key) {
        String value = resolve(key);
        return value != null && !value.isBlank();
    }

    boolean projectOnly(String key) {
        String projectValue = property(project.getProperties(), key);
        if (projectValue == null || projectValue.isBlank()) return false;
        return property(session.getUserProperties(), key) == null
                && property(session.getSystemProperties(), key) == null;
    }

    private String projectAlias(String key) {
        if (key == null) return null;
        return switch (key) {
            case "project.basedir", "basedir" -> project.getBasedir() == null
                    ? null : project.getBasedir().getAbsolutePath();
            case "project.build.directory" -> project.getBuild() == null ? null : project.getBuild().getDirectory();
            case "project.build.outputDirectory" -> project.getBuild() == null ? null : project.getBuild().getOutputDirectory();
            case "project.build.testOutputDirectory" -> project.getBuild() == null ? null : project.getBuild().getTestOutputDirectory();
            case "project.build.sourceDirectory" -> project.getBuild() == null ? null : project.getBuild().getSourceDirectory();
            case "project.build.testSourceDirectory" -> project.getBuild() == null ? null : project.getBuild().getTestSourceDirectory();
            case "project.artifactId" -> project.getArtifactId();
            case "project.groupId" -> project.getGroupId();
            case "project.version" -> project.getVersion();
            case "project.packaging" -> project.getPackaging();
            case "project.name" -> project.getName();
            default -> null;
        };
    }

    private String property(java.util.Properties source, String key) {
        if (source == null || key == null) return null;
        return source.getProperty(key);
    }
}
