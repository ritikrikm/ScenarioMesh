package io.scenariomesh.maven.extension;

import java.util.List;

/**
 * P0 ownership boundary for Maven command-line test selectors.
 *
 * <p>Surefire/Failsafe command selectors override configured includes/excludes. ScenarioMesh only
 * accepts selectors that the proven class-pattern parser can reproduce exactly. Method selectors,
 * negation and other richer grammar intentionally fail closed until the complete selector grammar
 * is implemented.</p>
 */
final class CommandLineClassSelection {
    private CommandLineClassSelection() {}

    static Analysis analyze(String executorName, String propertyName, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return Analysis.absent();
        try {
            return Analysis.supported(MavenClassNamePatterns.toRegexes(List.of(rawValue)));
        } catch (IllegalArgumentException unsupported) {
            return Analysis.unsupported(
                    executorName + " command-line selector '" + propertyName
                            + "' is outside ScenarioMesh's proven class-only Maven selector subset: "
                            + unsupported.getMessage());
        }
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
