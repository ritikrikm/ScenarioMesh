package io.scenariomesh.adapter.junitplatform;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed execution contracts for JUnit Platform engines.
 *
 * <p>An adapter declaring an engine id is necessary but not sufficient for ownership. Ownership is
 * granted only when the selected runtime also matches a proven engine identity, a compatible major
 * version family, a supported discovery shape, and the execution strategy ScenarioMesh actually
 * uses for that engine. This keeps engine detection separate from semantic proof.</p>
 */
final class JUnitPlatformExecutionContracts {
    private static final Pattern VERSION_MAJOR = Pattern.compile("^(\\d+)(?:\\.|$).*");
    private static final Set<String> SCOPED_CAPABILITIES = Set.of(
            "DISCOVERY", "STABLE_LEAF_IDENTITY", "LIFECYCLE_SCOPED_EXECUTION", "FILTER_EQUIVALENCE");

    private JUnitPlatformExecutionContracts() {}

    static Decision prove(Evidence evidence) {
        if (evidence.executableLeaves() <= 0) {
            return rejected("no executable leaves were discovered for engine '" + evidence.engineId() + "'");
        }
        if (!evidence.adapterDeclared()) {
            return rejected("adapter capabilities do not declare engine '" + evidence.engineId() + "'");
        }
        if (!identitySupported(evidence.engineId(), evidence.implementationClass(), evidence.version())) {
            return rejected("engine identity/version is outside the proven contract: "
                    + evidence.engineId() + "@" + evidence.version() + " (" + evidence.implementationClass() + ")");
        }
        if (evidence.discoveryShape() == DiscoveryShape.NONE) {
            return rejected("no proven discovery selector shape produced executable leaves for engine '"
                    + evidence.engineId() + "'");
        }

        return switch (evidence.engineId()) {
            case "junit-jupiter" -> scoped("jupiter-scoped-v1", evidence,
                    evidence.discoveryShape().hasClassSelection(),
                    "Jupiter ownership requires Maven-selected test classes");
            case "junit-vintage" -> scoped("vintage-scoped-v1", evidence,
                    evidence.discoveryShape().hasClassSelection(),
                    "Vintage ownership requires Maven-selected test classes");
            case "cucumber" -> scoped("cucumber-uniqueid-set-v1", evidence,
                    evidence.discoveryShape().hasClassSelection() || evidence.discoveryShape().hasClasspathResource(),
                    "Cucumber ownership requires a class/suite selection or a real classpath resource selection");
            case "junit-platform-suite" -> proveSuite(evidence);
            default -> rejected("no ScenarioMesh execution contract exists for engine '" + evidence.engineId() + "'");
        };
    }

    static boolean identitySupported(String engineId, String implementationClass, String version) {
        int major = versionMajor(version);
        return switch (engineId) {
            case "junit-jupiter" -> implementationClass.startsWith("org.junit.jupiter.engine.")
                    && (major == 5 || major == 6);
            case "junit-vintage" -> implementationClass.startsWith("org.junit.vintage.engine.")
                    && (major == 5 || major == 6);
            case "cucumber" -> implementationClass.startsWith("io.cucumber.junit.platform.engine.")
                    && major == 7;
            case "junit-platform-suite" -> implementationClass.startsWith("org.junit.platform.suite.engine.")
                    && (major == 1 || major == 6);
            default -> false;
        };
    }

    private static Decision proveSuite(Evidence evidence) {
        if (!evidence.discoveryShape().hasClassSelection()) {
            return rejected("JUnit Platform Suite ownership requires a Maven-selected suite class");
        }
        Set<String> nested = new LinkedHashSet<>(evidence.nestedEngineIds());
        nested.remove("junit-platform-suite");
        if (!evidence.provenNestedEngineIds().containsAll(nested)) {
            Set<String> unproven = new LinkedHashSet<>(nested);
            unproven.removeAll(evidence.provenNestedEngineIds());
            return rejected("suite contains nested engine(s) without a proven execution contract: " + unproven);
        }
        return accepted("platform-suite-scoped-v1", evidence,
                "suite class selection with proven nested engine contracts " + nested);
    }

    private static Decision scoped(String profile, Evidence evidence, boolean condition, String rejection) {
        return condition ? accepted(profile, evidence, "lifecycle-scoped execution") : rejected(rejection);
    }

    private static Decision accepted(String profile, Evidence evidence, String detail) {
        return new Decision(true, profile, "CONTAINER_OR_RUN", SCOPED_CAPABILITIES,
                profile + " proved for " + evidence.engineId() + "@" + evidence.version()
                        + " using " + evidence.discoveryShape() + " (" + detail + ")");
    }

    private static Decision rejected(String reason) {
        return new Decision(false, "unproven", "UNKNOWN",
                Set.of("DISCOVERY", "STABLE_LEAF_IDENTITY"), reason);
    }

    private static int versionMajor(String version) {
        if (version == null || version.isBlank()) return -1;
        Matcher matcher = VERSION_MAJOR.matcher(version.trim());
        if (!matcher.matches()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    enum DiscoveryShape {
        CLASS_SELECTION(true, false),
        CLASSPATH_RESOURCE(false, true),
        CLASS_AND_CLASSPATH_RESOURCE(true, true),
        NONE(false, false);

        private final boolean classSelection;
        private final boolean classpathResource;

        DiscoveryShape(boolean classSelection, boolean classpathResource) {
            this.classSelection = classSelection;
            this.classpathResource = classpathResource;
        }

        boolean hasClassSelection() { return classSelection; }
        boolean hasClasspathResource() { return classpathResource; }

        static DiscoveryShape of(boolean classSelection, boolean classpathResource) {
            if (classSelection && classpathResource) return CLASS_AND_CLASSPATH_RESOURCE;
            if (classSelection) return CLASS_SELECTION;
            if (classpathResource) return CLASSPATH_RESOURCE;
            return NONE;
        }
    }

    record Evidence(String engineId,
                    String implementationClass,
                    String version,
                    DiscoveryShape discoveryShape,
                    Set<String> nestedEngineIds,
                    Set<String> provenNestedEngineIds,
                    boolean adapterDeclared,
                    long executableLeaves) {
        Evidence {
            engineId = engineId == null ? "unknown" : engineId;
            implementationClass = implementationClass == null ? "unknown" : implementationClass;
            version = version == null || version.isBlank() ? "unknown" : version;
            discoveryShape = discoveryShape == null ? DiscoveryShape.NONE : discoveryShape;
            nestedEngineIds = Set.copyOf(nestedEngineIds == null ? Set.of() : nestedEngineIds);
            provenNestedEngineIds = Set.copyOf(provenNestedEngineIds == null ? Set.of() : provenNestedEngineIds);
        }
    }

    record Decision(boolean ownable,
                    String profile,
                    String granularity,
                    Set<String> capabilities,
                    String reason) {
        Decision {
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }
    }
}
