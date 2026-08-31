package io.scenariomesh.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.cli.CommandLineUtils;

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
            result.addAll(List.of(CommandLineUtils.translateCommandline(resolved.toString())));
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Surefire argLine cannot be tokenized using Maven command-line semantics: " + safeMessage(invalid), invalid);
        }
        return List.copyOf(result);
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
