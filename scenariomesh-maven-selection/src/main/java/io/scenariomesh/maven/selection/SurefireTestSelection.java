package io.scenariomesh.maven.selection;

import org.apache.maven.surefire.api.testset.TestListResolver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Shared adapter-facing wrapper around Surefire's public test-list resolver.
 *
 * <p>ScenarioMesh delegates Maven's test-list grammar to Surefire's public API instead of
 * maintaining an approximate parser. The same predicate is used by JUnit Platform and TestNG.</p>
 */
public final class SurefireTestSelection {
    private final String expression;
    private final List<String> included;
    private final List<String> excluded;
    private final TestListResolver resolver;

    public SurefireTestSelection(String expression) {
        this.expression = requireExpression(expression);
        this.included = List.of();
        this.excluded = List.of();
        this.resolver = new TestListResolver(this.expression);
    }

    public SurefireTestSelection(Collection<String> included, Collection<String> excluded) {
        this.expression = null;
        this.included = List.copyOf(included == null ? List.of() : included);
        this.excluded = List.copyOf(excluded == null ? List.of() : excluded);
        this.resolver = new TestListResolver(this.included, this.excluded);
    }

    public static SurefireTestSelection fromExpression(String expression) {
        return new SurefireTestSelection(expression);
    }

    public static SurefireTestSelection fromPatterns(Collection<String> included, Collection<String> excluded) {
        return new SurefireTestSelection(included, excluded);
    }

    public String expression() { return expression; }
    public List<String> included() { return included; }
    public List<String> excluded() { return excluded; }
    public boolean hasMethodPatterns() { return resolver.hasMethodPatterns(); }

    /** Matches Surefire's canonical compiled-class-file + method predicate. */
    public boolean matches(String binaryClassName, String methodName) {
        Objects.requireNonNull(binaryClassName, "binaryClassName");
        return resolver.shouldRun(toClassFileName(binaryClassName), methodName);
    }

    /** Keeps class containers discoverable when any selected method may live below them. */
    public boolean mayContainSelectedMethod(String binaryClassName) {
        Objects.requireNonNull(binaryClassName, "binaryClassName");
        if (resolver.hasMethodPatterns()) {
            String classFile = toClassFileName(binaryClassName);
            return resolver.getIncludedPatterns().isEmpty()
                    || resolver.getIncludedPatterns().stream().anyMatch(pattern -> pattern.matchAsInclusive(classFile, null));
        }
        return resolver.shouldRun(toClassFileName(binaryClassName), null);
    }

    public static void validate(String expression) { new SurefireTestSelection(expression); }
    public static void validate(Collection<String> included, Collection<String> excluded) {
        new SurefireTestSelection(included, excluded);
    }

    private static String toClassFileName(String binaryClassName) {
        return binaryClassName.replace('.', '/') + ".class";
    }

    private static String requireExpression(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Surefire test-list expression must not be blank");
        return value.trim();
    }
}
