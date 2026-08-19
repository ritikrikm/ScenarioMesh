package io.scenariomesh.core;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Framework-neutral class-level discovery boundary supplied by the build
 * integration. Adapters use it before asking their framework to discover tests,
 * so ScenarioMesh can preserve Maven executor class selection without importing
 * Maven concepts into core or framework adapters.
 */
public record DiscoverySelection(List<String> includeClassNameRegexes,
                                 List<String> excludeClassNameRegexes) {

    public DiscoverySelection {
        includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
        excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
        includeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
        excludeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
    }

    public static DiscoverySelection all() {
        return new DiscoverySelection(List.of(), List.of());
    }

    public boolean matchesClassName(String className) {
        Objects.requireNonNull(className, "className");
        boolean included = includeClassNameRegexes.isEmpty()
                || includeClassNameRegexes.stream().anyMatch(regex -> Pattern.matches(regex, className));
        if (!included) {
            return false;
        }
        return excludeClassNameRegexes.stream().noneMatch(regex -> Pattern.matches(regex, className));
    }

    private static void validateRegex(String regex) {
        Objects.requireNonNull(regex, "regex");
        Pattern.compile(regex);
    }
}
