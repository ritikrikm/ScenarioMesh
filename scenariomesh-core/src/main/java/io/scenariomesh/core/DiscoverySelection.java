package io.scenariomesh.core;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Framework-neutral discovery boundary supplied by an outer build integration. */
public record DiscoverySelection(List<String> includeClassNameRegexes,
                                 List<String> excludeClassNameRegexes,
                                 String testListExpression,
                                 List<String> includedTestPatterns,
                                 List<String> excludedTestPatterns) {

    public DiscoverySelection(List<String> includeClassNameRegexes,
                              List<String> excludeClassNameRegexes) {
        this(includeClassNameRegexes, excludeClassNameRegexes, null, List.of(), List.of());
    }

    public DiscoverySelection(List<String> includeClassNameRegexes,
                              List<String> excludeClassNameRegexes,
                              String testListExpression) {
        this(includeClassNameRegexes, excludeClassNameRegexes, testListExpression, List.of(), List.of());
    }

    public DiscoverySelection {
        includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
        excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
        includedTestPatterns = List.copyOf(includedTestPatterns == null ? List.of() : includedTestPatterns);
        excludedTestPatterns = List.copyOf(excludedTestPatterns == null ? List.of() : excludedTestPatterns);
        includeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
        excludeClassNameRegexes.forEach(DiscoverySelection::validateRegex);
        if (testListExpression != null) {
            testListExpression = testListExpression.trim();
            if (testListExpression.isEmpty()) testListExpression = null;
        }
        if (testListExpression != null && (!includedTestPatterns.isEmpty() || !excludedTestPatterns.isEmpty())) {
            throw new IllegalArgumentException("Maven command test-list expression and configured pattern collections are mutually exclusive");
        }
    }

    public static DiscoverySelection all() {
        return new DiscoverySelection(List.of(), List.of());
    }

    public boolean hasTestListExpression() { return testListExpression != null; }
    public boolean hasConfiguredTestPatterns() { return !includedTestPatterns.isEmpty() || !excludedTestPatterns.isEmpty(); }
    public boolean hasMavenTestSelection() { return hasTestListExpression() || hasConfiguredTestPatterns(); }

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
