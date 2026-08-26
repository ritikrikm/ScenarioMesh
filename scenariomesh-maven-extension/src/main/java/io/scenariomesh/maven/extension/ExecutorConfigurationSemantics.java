package io.scenariomesh.maven.extension;

import java.util.Map;
import java.util.Set;

/**
 * Shared semantic classification for Surefire/Failsafe configuration.
 *
 * <p>This class deliberately separates settings ScenarioMesh replaces from settings
 * that must be reproduced exactly. A newly introduced Maven option therefore does not
 * become accidentally accepted just because it looks harmless.</p>
 */
final class ExecutorConfigurationSemantics {
    private ExecutorConfigurationSemantics() {}

    enum Kind {
        /** Native executor concurrency is intentionally replaced by ScenarioMesh workers. */
        REPLACED_BY_SCENARIOMESH,
        /** The compatibility layer has an explicit implementation for this setting. */
        PRESERVED,
        /** Known Maven feature, but takeover requires a capability not implemented yet. */
        REQUIRES_CAPABILITY,
        /** Unknown/new setting: fail closed until reviewed. */
        UNKNOWN
    }

    record Classification(Kind kind, String capability) {
        static Classification replaced() {
            return new Classification(Kind.REPLACED_BY_SCENARIOMESH, null);
        }
        static Classification preserved() {
            return new Classification(Kind.PRESERVED, null);
        }
        static Classification requires(String capability) {
            return new Classification(Kind.REQUIRES_CAPABILITY, capability);
        }
        static Classification unknown() {
            return new Classification(Kind.UNKNOWN, null);
        }
    }

    private static final Set<String> CONCURRENCY = Set.of(
            "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
            "threadCountMethods", "threadCountSuites", "perCoreThreadCount",
            "useUnlimitedThreads", "parallelOptimized");

    private static final Set<String> COMMON_PRESERVED = Set.of(
            "skip", "skipTests", "useModulePath", "enableAssertions");

    private static final Set<String> FAILSAFE_PRESERVED = Set.of(
            "skipITs", "includes", "excludes", "argLine", "systemPropertyVariables",
            "testFailureIgnore", "rerunFailingTestsCount");

    private static final Map<String, String> CAPABILITY_REQUIRED = Map.ofEntries(
            Map.entry("groups", "framework-group-selection"),
            Map.entry("excludedGroups", "framework-group-selection"),
            Map.entry("suiteXmlFiles", "suite-xml-selection"),
            Map.entry("dependenciesToScan", "dependency-test-scanning"),
            Map.entry("additionalClasspathElements", "executor-classpath-extension"),
            Map.entry("additionalClasspathDependencies", "executor-classpath-extension"),
            Map.entry("classpathDependencyExclude", "executor-classpath-filtering"),
            Map.entry("classpathDependencyScopeExclude", "executor-classpath-filtering"),
            Map.entry("environmentVariables", "fork-environment-reproduction"),
            Map.entry("workingDirectory", "fork-working-directory"),
            Map.entry("jvm", "alternate-jvm-selection"),
            Map.entry("jdkToolchain", "maven-toolchain-selection"),
            Map.entry("includesFile", "external-selection-file"),
            Map.entry("excludesFile", "external-selection-file"));

    static Classification forSurefire(String name) {
        if (CONCURRENCY.contains(name)) return Classification.replaced();
        if (COMMON_PRESERVED.contains(name)) return Classification.preserved();
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }

    static Classification forFailsafe(String name) {
        if (CONCURRENCY.contains(name)) return Classification.replaced();
        if (COMMON_PRESERVED.contains(name) || FAILSAFE_PRESERVED.contains(name)) {
            return Classification.preserved();
        }
        String capability = CAPABILITY_REQUIRED.get(name);
        return capability == null ? Classification.unknown() : Classification.requires(capability);
    }
}
