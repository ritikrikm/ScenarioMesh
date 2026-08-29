package io.scenariomesh.core;

/** Internal runtime property contract shared between Maven integration and worker adapters. */
public final class RuntimePropertyNames {
    private RuntimePropertyNames() {}

    public static final String CLUECUMBER_JSON_DIRECTORY =
            "scenariomesh.compat.cluecumber.sourceJsonReportDirectory";

    /** Consumed by discovery/preflight only and removed before target tests execute. */
    public static final String MAVEN_TEST_LIST_EXPRESSION =
            "scenariomesh.internal.maven.testListExpression";
}
