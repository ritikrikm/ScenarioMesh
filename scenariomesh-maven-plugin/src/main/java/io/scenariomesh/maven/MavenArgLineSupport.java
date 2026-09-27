package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves Surefire's late @{...} argLine syntax at the point the test goal actually executes. */
final class MavenArgLineSupport {
    private static final Pattern LATE_PROPERTY_REFERENCE = Pattern.compile("@\\{([^}]+)}");

    private MavenArgLineSupport() {}

    static List<String> merge(List<String> configuredJvmArgs,
                              String executorArgLine,
                              MavenProject project,
                              MavenSession session) {
        return merge(
                configuredJvmArgs,
                executorArgLine,
                project == null ? null : project.getProperties(),
                session == null ? null : session.getSystemProperties(),
                session == null ? null : session.getUserProperties());
    }

    static List<String> merge(List<String> configuredJvmArgs,
                              String executorArgLine,
                              Properties projectProperties,
                              Properties systemProperties,
                              Properties userProperties) {
        List<String> result = new ArrayList<>(configuredJvmArgs == null ? List.of() : configuredJvmArgs);
        if (executorArgLine == null || executorArgLine.isBlank()) return List.copyOf(result);

        Map<String, String> lateProperties = new LinkedHashMap<>();
        copy(projectProperties, lateProperties);
        copy(systemProperties, lateProperties);
        copy(userProperties, lateProperties);

        Matcher matcher = LATE_PROPERTY_REFERENCE.matcher(executorArgLine);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            // Surefire documents a missing late property as an empty-string replacement.
            String replacement = lateProperties.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);

        try {
            result.addAll(tokenizeLikeSurefire(resolved.toString().replaceAll("\\s", " ")));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Surefire argLine cannot be tokenized using Surefire command-line semantics: " + safeMessage(invalid),
                    invalid);
        }
        return List.copyOf(result);
    }

    /**
     * Mirrors the command-line tokenization used by Maven Shared Utils in the Surefire lines
     * ScenarioMesh owns (Surefire 3.5.2 and 3.6.x) without depending on Maven's plugin realm.
     */
    static List<String> tokenizeLikeSurefire(String line) {
        if (line == null || line.isEmpty()) return List.of();

        final int normal = 0;
        final int singleQuoted = 1;
        final int doubleQuoted = 2;
        int state = normal;
        boolean escaped = false;
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int index = 0; index < line.length(); index++) {
            char next = line.charAt(index);
            if (state == singleQuoted) {
                if (next == '\'') {
                    if (escaped) {
                        current.append(next);
                        escaped = false;
                    } else {
                        state = normal;
                    }
                } else {
                    current.append(next);
                    escaped = next == '\\';
                }
                continue;
            }
            if (state == doubleQuoted) {
                if (next == '"') {
                    if (escaped) {
                        current.append(next);
                        escaped = false;
                    } else {
                        state = normal;
                    }
                } else {
                    current.append(next);
                    escaped = next == '\\';
                }
                continue;
            }

            if (next == '\'') {
                if (escaped) {
                    escaped = false;
                    current.append(next);
                } else {
                    state = singleQuoted;
                }
            } else if (next == '"') {
                if (escaped) {
                    escaped = false;
                    current.append(next);
                } else {
                    state = doubleQuoted;
                }
            } else if (next == ' ') {
                if (current.length() != 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(next);
                escaped = next == '\\';
            }
        }

        if (current.length() != 0) tokens.add(current.toString());
        if (state != normal) throw new IllegalArgumentException("unbalanced quotes in " + line);
        return List.copyOf(tokens);
    }

    private static void copy(Properties source, Map<String, String> target) {
        if (source == null) return;
        source.forEach((key, value) -> target.put(String.valueOf(key), String.valueOf(value)));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
