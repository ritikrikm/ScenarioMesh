package io.scenariomesh.maven.selection;

import org.apache.maven.surefire.api.testset.TestListResolver;

import java.util.Objects;

/**
 * Shared adapter-facing wrapper around Surefire's public test-list resolver.
 *
 * <p>ScenarioMesh deliberately delegates Maven's advanced {@code test}/{@code it.test}
 * grammar to Surefire's public API instead of maintaining an approximate parser. The
 * same predicate is used by JUnit Platform and TestNG discovery.</p>
 */
public final class SurefireTestSelection {
    private final String expression;
    private final TestListResolver resolver;

    public SurefireTestSelection(String expression) {
        this.expression = requireExpression(expression);
        this.resolver = new TestListResolver(this.expression);
    }

    public String expression() {
        return expression;
    }

    public boolean hasMethodPatterns() {
        return resolver.hasMethodPatterns();
    }

    /** Matches Surefire's canonical compiled-class-file + method predicate. */
    public boolean matches(String binaryClassName, String methodName) {
        Objects.requireNonNull(binaryClassName, "binaryClassName");
        return resolver.shouldRun(toClassFileName(binaryClassName), methodName);
    }

    /**
     * Class containers must remain discoverable when method patterns are present so their
     * method descriptors can be evaluated individually. For class-only expressions the
     * resolver itself is authoritative.
     */
    public boolean mayContainSelectedMethod(String binaryClassName) {
        Objects.requireNonNull(binaryClassName, "binaryClassName");
        if (resolver.hasMethodPatterns()) {
            String classFile = toClassFileName(binaryClassName);
            return resolver.getIncludedPatterns().isEmpty()
                    || resolver.getIncludedPatterns().stream().anyMatch(pattern -> pattern.matchAsInclusive(classFile, null));
        }
        return resolver.shouldRun(toClassFileName(binaryClassName), null);
    }

    public static void validate(String expression) {
        new SurefireTestSelection(expression);
    }

    private static String toClassFileName(String binaryClassName) {
        return binaryClassName.replace('.', '/') + ".class";
    }

    private static String requireExpression(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Surefire test-list expression must not be blank");
        }
        return value.trim();
    }
}
