package io.scenariomesh.core;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Framework-neutral discovery boundary supplied by an outer build integration.
 *
 * <p>Class selection is normalized to regular expressions before crossing this boundary.
 * The optional test-list expression is deliberately opaque to core: integrations may attach
 * a richer class+method expression and adapters that understand that expression can apply it
 * without core reinterpreting build-tool syntax.</p>
 */
public record DiscoverySelection(List<String> includeClassNameRegexes,
                                 List<String> excludeClassNameRegexes,
                                 String testListExpression) {

    public DiscoverySelection(List<String> includeClassNameRegexes,
                              List<String> excludeClassNameRegexes) {
        this(includeClassNameRegexes, excludeClassNameRegexes, null);
    }

    public DiscoverySelection {
        includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
        excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
        includeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
        excludeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
        if (testListExpression != null) {
            testListExpression = testListExpression.trim();
            if (testListExpression.isEmpty()) testListExpression = null;
        }
    }

    public static DiscoverySelection all() {
        return new DiscoverySelection(List.of(), List.of(), null);
    }

    public boolean hasTestListExpression() {
        return testListExpression != null;
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
