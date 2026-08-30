package io.scenariomesh.maven.extension;

import io.scenariomesh.maven.selection.SurefireTestSelection;

import java.util.List;

/** Models Surefire/Failsafe command-line test selectors without approximating their grammar. */
final class CommandLineClassSelection {
    private CommandLineClassSelection() {}

    static Analysis analyze(String executorName, String propertyName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return Analysis.absent();

        try {
            String expression = rawValue.trim();
            SurefireTestSelection.validate(expression);
            // Always let Surefire's public TestListResolver own the final class/method decision.
            // Discovery stays intentionally broad so no class that the resolver could select is
            // discarded by an approximate ScenarioMesh regex before adapter-level filtering.
            return Analysis.supported(List.of(".*"), expression);
        } catch (RuntimeException invalidSurefireExpression) {
            return Analysis.unsupported(
                    executorName + " command-line selector '" + propertyName
                            + "' cannot be reproduced by Surefire's public TestListResolver: "
                            + message(invalidSurefireExpression));
        }
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
