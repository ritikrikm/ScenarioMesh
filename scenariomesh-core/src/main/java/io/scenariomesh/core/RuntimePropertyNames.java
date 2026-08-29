package io.scenariomesh.core;

/** Internal runtime property contract shared between Maven integration and worker adapters. */
public final class RuntimePropertyNames {
    private RuntimePropertyNames() {}

    public static final String INTERNAL_PREFIX = "scenariomesh.internal.";

    public static final String CLUECUMBER_JSON_DIRECTORY =
            "scenariomesh.compat.cluecumber.sourceJsonReportDirectory";

    /** Consumed by discovery/preflight only and removed before target tests execute. */
    public static final String MAVEN_TEST_LIST_EXPRESSION =
            INTERNAL_PREFIX + "maven.testListExpression";
    public static final String MAVEN_INCLUDED_TEST_PATTERNS =
            INTERNAL_PREFIX + "maven.includedTestPatterns";
    public static final String MAVEN_EXCLUDED_TEST_PATTERNS =
            INTERNAL_PREFIX + "maven.excludedTestPatterns";

    /** Prevents Vintage from duplicating tests owned by the dedicated Cucumber JUnit 4 adapter. */
    public static final String JUNIT_VINTAGE_DISABLED =
            INTERNAL_PREFIX + "junit.disableVintage";
}
