package io.scenariomesh.core;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Framework-neutral class-level discovery boundary supplied by the build
 * integration. Maven-derived selectors are matched against canonical class-file
 * paths (for example {@code com/acme/LoginIT.class}) because that is the surface
 * Surefire/Failsafe regex/glob selection is defined against. For compatibility
 * with existing ScenarioMesh-native regexes, the dotted class name is also
 * accepted as a secondary representation.
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
        String classFilePath = className.replace('.', '/') + ".class";
        boolean included = includeClassNameRegexes.isEmpty()
                || includeClassNameRegexes.stream().anyMatch(regex -> matches(regex, className, classFilePath));
        if (!included) return false;
        return excludeClassNameRegexes.stream().noneMatch(regex -> matches(regex, className, classFilePath));
    }

    private static boolean matches(String regex, String className, String classFilePath) {
        Pattern pattern = Pattern.compile(regex);
        return pattern.matcher(classFilePath).matches() || pattern.matcher(className).matches();
    }

    private static void validateRegex(String regex) {
        Objects.requireNonNull(regex, "regex");
        Pattern.compile(regex);
    }
}
