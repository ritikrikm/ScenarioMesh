package io.scenariomesh.maven.extension;

import io.scenariomesh.maven.selection.SurefireTestSelection;

import java.util.ArrayList;
import java.util.List;

/** Models Surefire/Failsafe command-line test selectors without approximating advanced grammar. */
final class CommandLineClassSelection {
    private CommandLineClassSelection() {}

    static Analysis analyze(String executorName, String propertyName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return Analysis.absent();

        // Keep the compact class-regex path for the already-proven common subset.
        try {
            return Analysis.supported(
                    MavenClassNamePatterns.toRegexes(normalizeSimpleSelectors(rawValue)), null);
        } catch (IllegalArgumentException outsideSimpleSubset) {
            // P1: delegate the complete multi-pattern class+method grammar to Surefire's public API.
            try {
                SurefireTestSelection.validate(rawValue);
                // Method/negation/regex selection is applied by adapter-level SurefireTestSelection.
                // Keep class discovery broad so no potentially selected method is lost prematurely.
                return Analysis.supported(List.of(".*"), rawValue.trim());
            } catch (RuntimeException invalidSurefireExpression) {
                return Analysis.unsupported(
                        executorName + " command-line selector '" + propertyName
                                + "' cannot be reproduced by Surefire's public TestListResolver: "
                                + message(invalidSurefireExpression));
            }
        }
    }

    private static List<String> normalizeSimpleSelectors(String rawValue) {
        List<String> normalized = new ArrayList<>();
        for (String rawToken : rawValue.split(",", -1)) {
            String token = rawToken.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("empty command-line class selector");
            if (token.startsWith("!") || token.contains("#")) MavenClassNamePatterns.toRegex(token);
            if (token.startsWith("%regex[") || token.contains("/") || token.contains("\\")) {
                throw new IllegalArgumentException("advanced selector syntax");
            }

            String withoutSuffix = token;
            if (withoutSuffix.endsWith(".java")) withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 5);
            else if (withoutSuffix.endsWith(".class")) withoutSuffix = withoutSuffix.substring(0, withoutSuffix.length() - 6);
            if (withoutSuffix.contains(".")) throw new IllegalArgumentException("package-qualified selector syntax");

            normalized.add("**/" + token);
        }
        return List.copyOf(normalized);
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    record Analysis(boolean present,
                    boolean supported,
                    List<String> includeRegexes,
                    String testListExpression,
                    String reason) {
        Analysis {
            includeRegexes = List.copyOf(includeRegexes == null ? List.of() : includeRegexes);
        }

        static Analysis absent() { return new Analysis(false, true, List.of(), null, null); }
        static Analysis supported(List<String> includeRegexes, String testListExpression) {
            return new Analysis(true, true, includeRegexes, testListExpression, null);
        }
        static Analysis unsupported(String reason) { return new Analysis(true, false, List.of(), null, reason); }
    }
}
