package io.scenariomesh.maven.extension;

import java.util.Map;
import java.util.Set;

/** Shared semantic classification for Surefire/Failsafe configuration. */
final class ExecutorConfigurationSemantics {
    private ExecutorConfigurationSemantics() {}

    enum Kind { REPLACED_BY_SCENARIOMESH, PRESERVED, REQUIRES_CAPABILITY, UNKNOWN }

    record Classification(Kind kind, String capability) {
        static Classification replaced() { return new Classification(Kind.REPLACED_BY_SCENARIOMESH, null); }
        static Classification preserved() { return new Classification(Kind.PRESERVED, null); }
        static Classification requires(String capability) { return new Classification(Kind.REQUIRES_CAPABILITY, capability); }
        static Classification unknown() { return new Classification(Kind.UNKNOWN, null); }
    }

    private static final Set<String> SCENARIOMESH_OWNED = Set.of(
            "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
            "threadCountMethods", "threadCountSuites", "perCoreThreadCount",
            "useUnlimitedThreads", "parallelOptimized",
            "jvm", "jdkToolchain",
            "enableAssertions", "environmentVariables", "excludedEnvironmentVariables", "workingDirectory",
            "additionalClasspathElements", "additionalClasspathDependencies",
            "classpathDependencyExcludes", "classpathDependencyScopeExclude");

    /**
     * These are consumed by EffectiveExecutorSystemProperties as one semantic
     * unit. Keeping them out of the per-node preservation switches prevents
     * accidental precedence drift as new executor configuration is added.
     */
    private static final Set<String> EFFECTIVE_SYSTEM_PROPERTY_SOURCES = Set.of(
            "systemProperties",
            "systemPropertiesFile",
            "systemPropertyVariables",
            "promoteUserPropertiesToSystemProperties");

    private static final Set<String> COMMON_PRESERVED = Set.of(
            "skip", "skipTests", "useModulePath");

    private static final Set<String> SUREFIRE_PRESERVED = Set.of(
            "includes", "excludes", "includesFile", "excludesFile", "dependenciesToScan",
            "includeJUnit5Engines", "excludeJUnit5Engines",
            "groups", "excludedGroups",
            "properties", "suiteXmlFiles");

    private static final Set<String> FAILSAFE_PRESERVED = Set.of(
            "skipITs", "includes", "excludes", "includesFile", "excludesFile", "dependenciesToScan",
            "includeJUnit5Engines", "excludeJUnit5Engines",
            "groups", "excludedGroups",
            "argLine", "testFailureIgnore", "rerunFailingTestsCount",
            "suiteXmlFiles");

    private static final Map<String, String> CAPABILITY_REQUIRED = Map.of();

    static Classification forSurefire(String name) {
        if (SCENARIOMESH_OWNED.contains(name) || EFFECTIVE_SYSTEM_PROPERTY_SOURCES.contains(name)) {
            return Classification.replaced();
        }
        if (COMMON_PRESERVED.contains(name) || SUREFIRE_PRESERVED.contains(name)) return Classification.preserved();
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }

    static Classification forFailsafe(String name) {
        if (SCENARIOMESH_OWNED.contains(name) || EFFECTIVE_SYSTEM_PROPERTY_SOURCES.contains(name)) {
            return Classification.replaced();
        }
        if (COMMON_PRESERVED.contains(name) || FAILSAFE_PRESERVED.contains(name)) return Classification.preserved();
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }
}
