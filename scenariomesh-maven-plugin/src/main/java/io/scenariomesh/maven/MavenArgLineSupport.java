package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

/** Resolves Surefire's late @{...} argLine syntax at the point the test goal actually executes. */
final class MavenArgLineSupport {
    private MavenArgLineSupport() {}

    static List<String> merge(List<String> configuredJvmArgs,
                              String executorArgLine,
                              MavenProject project,
                              MavenSession session) {
        // Surefire passes getProject().getModel().getProperties() to its fork configuration.
        // Session user/system properties are not an extra late-expression overlay here.
        Properties modelProperties = project == null || project.getModel() == null
                ? null : project.getModel().getProperties();
        return merge(configuredJvmArgs, executorArgLine, modelProperties);
    }

    static List<String> merge(List<String> configuredJvmArgs,
                              String executorArgLine,
                              Properties modelProperties) {
        List<String> result = new ArrayList<>(configuredJvmArgs == null ? List.of() : configuredJvmArgs);
        if (executorArgLine == null) return List.copyOf(result);

        // Match Surefire DefaultForkConfiguration#interpolateArgLineWithPropertyExpressions:
        // trim first, then replace only late-expression keys present in model properties.
        String resolvedArgLine = executorArgLine.trim();
        if (resolvedArgLine.isEmpty()) return List.copyOf(result);
        if (modelProperties != null) {
            for (String key : modelProperties.stringPropertyNames()) {
                String field = "@{" + key + "}";
                if (executorArgLine.contains(field)) {
                    resolvedArgLine = resolvedArgLine.replace(field, modelProperties.getProperty(key, ""));
                }
            }
        }

        try {
            // Surefire normalizes whitespace before Commandline.Argument#setLine().
            result.addAll(tokenizeLikeSurefire(resolvedArgLine.replaceAll("\\s", " ")));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Surefire argLine cannot be tokenized using Surefire command-line semantics: "
                            + safeMessage(invalid),
                    invalid);
        }
        return List.copyOf(result);
    }

    /**
     * Reproduces Maven Shared Utils CommandLineUtils#translateCommandline, which Surefire shades
     * into org.apache.maven.surefire.shared.utils. The relevant state machine is identical in
     * Maven Shared Utils 3.3.4 (Surefire 3.5.2) and 3.4.2 (Surefire 3.6.0).
     */
    static List<String> tokenizeLikeSurefire(String line) {
        if (line == null || line.isEmpty()) return List.of();

        final int normal = 0;
        final int singleQuoted = 1;
        final int doubleQuoted = 2;
        boolean escaped = false;
        int state = normal;
        StringTokenizer tokenizer = new StringTokenizer(line, "\"' \\", true);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        while (tokenizer.hasMoreTokens()) {
            String next = tokenizer.nextToken();
            switch (state) {
                case singleQuoted:
                    if ("'".equals(next)) {
                        if (escaped) {
                            current.append(next);
                            escaped = false;
                        } else {
                            state = normal;
                        }
                    } else {
                        current.append(next);
                        escaped = "\\".equals(next);
                    }
                    break;
                case doubleQuoted:
                    if ("\"".equals(next)) {
                        if (escaped) {
                            current.append(next);
                            escaped = false;
                        } else {
                            state = normal;
                        }
                    } else {
                        current.append(next);
                        escaped = "\\".equals(next);
                    }
                    break;
                default:
                    if ("'".equals(next)) {
                        if (escaped) {
                            escaped = false;
                            current.append(next);
                        } else {
                            state = singleQuoted;
                        }
                    } else if ("\"".equals(next)) {
                        if (escaped) {
                            escaped = false;
                            current.append(next);
                        } else {
                            state = doubleQuoted;
                        }
                    } else if (" ".equals(next)) {
                        if (current.length() != 0) {
                            tokens.add(current.toString());
                            current.setLength(0);
                        }
                    } else {
                        current.append(next);
                        escaped = "\\".equals(next);
                    }
                    break;
            }
        }

        if (current.length() != 0) tokens.add(current.toString());
        if (state != normal) throw new IllegalArgumentException("unbalanced quotes in " + line);
        return List.copyOf(tokens);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
