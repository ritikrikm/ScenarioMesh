package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.maven.selection.MavenSelectionCodec;
import io.scenariomesh.maven.selection.SurefireTestSelection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chooses the fast class-only path or Surefire's public full test-list semantics. */
final class ConfiguredTestSelection {
    private ConfiguredTestSelection() {}

    static Plan analyze(List<String> configuredIncludes,
                        List<String> configuredExcludes,
                        List<String> defaultIncludes,
                        List<String> defaultExcludes) {
        List<String> includes = configuredIncludes == null || configuredIncludes.isEmpty()
                ? List.copyOf(defaultIncludes) : List.copyOf(configuredIncludes);
        List<String> excludes = configuredExcludes == null || configuredExcludes.isEmpty()
                ? List.copyOf(defaultExcludes) : List.copyOf(configuredExcludes);
        try {
            return Plan.fast(MavenClassNamePatterns.toRegexes(includes), MavenClassNamePatterns.toRegexes(excludes));
        } catch (IllegalArgumentException advanced) {
            try {
                SurefireTestSelection.validate(includes, excludes);
                Map<String, String> properties = new LinkedHashMap<>();
                properties.put(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS, MavenSelectionCodec.encode(includes));
                properties.put(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS, MavenSelectionCodec.encode(excludes));
                return Plan.delegated(properties);
            } catch (RuntimeException invalid) {
                return Plan.unsupported("Surefire TestListResolver rejected configured test patterns: " + message(invalid));
            }
        }
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    record Plan(boolean supported,
                List<String> includeClassNameRegexes,
                List<String> excludeClassNameRegexes,
                Map<String, String> internalProperties,
                String reason) {
        Plan {
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            internalProperties = Map.copyOf(internalProperties == null ? Map.of() : internalProperties);
        }
        static Plan fast(List<String> includes, List<String> excludes) {
            return new Plan(true, includes, excludes, Map.of(), null);
        }
        static Plan delegated(Map<String, String> properties) {
            return new Plan(true, List.of(".*"), List.of(), properties, null);
        }
        static Plan unsupported(String reason) {
            return new Plan(false, List.of(), List.of(), Map.of(), reason);
        }
    }
}
