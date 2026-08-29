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

    private static final Set<String> CONCURRENCY_OR_LAUNCH_OWNED = Set.of(
            "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
            "threadCountMethods", "threadCountSuites", "perCoreThreadCount",
            "useUnlimitedThreads", "parallelOptimized",
            "jvm", "jdkToolchain",
            // These settings are reproduced by MavenForkLaunchConfiguration plus
            // the selected-JVM preflight/discovery/worker launch path.
            "enableAssertions", "workingDirectory");

    private static final Set<String> COMMON_PRESERVED = Set.of(
            "skip", "skipTests", "useModulePath");

    private static final Set<String> SUREFIRE_PRESERVED = Set.of(
            "includes", "excludes", "includesFile", "excludesFile",
            "includeJUnit5Engines", "excludeJUnit5Engines",
            "systemPropertyVariables", "properties", "suiteXmlFiles");

    private static final Set<String> FAILSAFE_PRESERVED = Set.of(
            "skipITs", "includes", "excludes", "includesFile", "excludesFile",
            "includeJUnit5Engines", "excludeJUnit5Engines",
            "argLine", "systemPropertyVariables", "testFailureIgnore", "rerunFailingTestsCount");

    private static final Map<String, String> CAPABILITY_REQUIRED = Map.ofEntries(
            Map.entry("groups", "framework-group-selection"),
            Map.entry("excludedGroups", "framework-group-selection"),
            Map.entry("dependenciesToScan", "dependency-test-scanning"),
            Map.entry("additionalClasspathElements", "executor-classpath-extension"),
            Map.entry("additionalClasspathDependencies", "executor-classpath-extension"),
            Map.entry("classpathDependencyExcludes", "executor-classpath-filtering"),
            Map.entry("classpathDependencyScopeExclude", "executor-classpath-filtering"),
            // These remain fail-closed until local and remote worker process environments
            // can both reproduce Surefire/Failsafe's inherited-environment overlay exactly.
            Map.entry("environmentVariables", "fork-environment-reproduction"),
            Map.entry("excludedEnvironmentVariables", "fork-environment-reproduction"));

    static Classification forSurefire(String name) {
        if (CONCURRENCY_OR_LAUNCH_OWNED.contains(name)) return Classification.replaced();
        if (COMMON_PRESERVED.contains(name) || SUREFIRE_PRESERVED.contains(name)) return Classification.preserved();
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }

    static Classification forFailsafe(String name) {
        if (CONCURRENCY_OR_LAUNCH_OWNED.contains(name)) return Classification.replaced();
        if (COMMON_PRESERVED.contains(name) || FAILSAFE_PRESERVED.contains(name)) return Classification.preserved();
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }
}
