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

    /** Internal handoff from Maven compatibility analysis to the ScenarioMesh run mojo. */
    public static final String MAVEN_EXECUTOR_ARG_LINE =
            INTERNAL_PREFIX + "maven.executorArgLine";
    public static final String MAVEN_ZERO_TEST_POLICY_ENABLED =
            INTERNAL_PREFIX + "maven.zeroTestPolicyEnabled";
    public static final String MAVEN_FAIL_IF_NO_TESTS =
            INTERNAL_PREFIX + "maven.failIfNoTests";
    public static final String MAVEN_FAIL_IF_NO_SPECIFIED_TESTS =
            INTERNAL_PREFIX + "maven.failIfNoSpecifiedTests";
    public static final String MAVEN_EXPLICIT_TEST_SELECTION =
            INTERNAL_PREFIX + "maven.explicitTestSelection";
    public static final String MAVEN_PROMOTE_USER_PROPERTIES =
            INTERNAL_PREFIX + "maven.promoteUserPropertiesToSystemProperties";

    /** Prevents Vintage from duplicating tests owned by the dedicated Cucumber JUnit 4 adapter. */
    public static final String JUNIT_VINTAGE_DISABLED =
            INTERNAL_PREFIX + "junit.disableVintage";
}
