package io.scenariomesh.maven.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * P0 ownership boundary for Maven command-line test selectors.
 *
 * <p>Surefire/Failsafe command selectors override configured includes/excludes. ScenarioMesh only
 * accepts the documented simple-class/wildcard subset that it can reproduce exactly. Maven's
 * {@code -Dtest=MyTest} form is intentionally package-independent, so bare class selectors are
 * translated to the equivalent compiled-class scan pattern before crossing the discovery boundary.
 * Method selectors, negation, regex and package-qualified/path syntax intentionally fail closed
 * until the complete selector grammar is implemented.</p>
 */
final class CommandLineClassSelection {
    private CommandLineClassSelection() {}

    static Analysis analyze(String executorName, String propertyName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return Analysis.absent();
        try {
            return Analysis.supported(MavenClassNamePatterns.toRegexes(normalizeP0Selectors(rawValue)));
        } catch (IllegalArgumentException unsupported) {
            return Analysis.unsupported(
                    executorName + " command-line selector '" + propertyName
                            + "' is outside ScenarioMesh's proven class-only Maven selector subset: "
                            + unsupported.getMessage());
        }
    }

    private static List<String> normalizeP0Selectors(String rawValue) {
        List<String> normalized = new ArrayList<>();
        for (String rawToken : rawValue.split(",", -1)) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("empty command-line class selector");
            }
            if (token.startsWith("!") || token.contains("#")) {
                // Delegate these to the shared parser so the existing precise reason is retained.
                MavenClassNamePatterns.toRegex(token);
            }
            if (token.startsWith("%regex[") || token.contains("/") || token.contains("\\")) {
                throw new IllegalArgumentException(
                        "unsupported Maven command-line class selector '" + token
                                + "': regex/path syntax is outside the P0 simple-class subset");
            }

            String withoutSuffix = token;
            if (withoutSuffix.endsWith(".java")) {
                withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 5);
            } else if (withoutSuffix.endsWith(".class")) {
                withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 6);
            }
            if (withoutSuffix.contains(".")) {
                throw new IllegalArgumentException(
                        "unsupported Maven command-line class selector '" + token
                                + "': package-qualified syntax is outside the P0 simple-class subset");
            }

            normalized.add("**/" + token);
        }
        return List.copyOf(normalized);
    }

    record Analysis(boolean present, boolean supported, List<String> includeRegexes, String reason) {
        Analysis {
            includeRegexes = List.copyOf(includeRegexes == null ? List.of() : includeRegexes);
        }

        static Analysis absent() {
            return new Analysis(false, true, List.of(), null);
        }

        static Analysis supported(List<String> includeRegexes) {
            return new Analysis(true, true, includeRegexes, null);
        }

        static Analysis unsupported(String reason) {
            return new Analysis(true, false, List.of(), reason);
        }
    }
}
