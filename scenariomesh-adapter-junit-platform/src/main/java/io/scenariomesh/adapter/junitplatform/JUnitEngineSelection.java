package io.scenariomesh.adapter.junitplatform;

import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Applies the exact Surefire/Failsafe JUnit Platform provider engine properties. */
final class JUnitEngineSelection {
    static final String INCLUDE_PROPERTY = "includejunit5engines";
    static final String EXCLUDE_PROPERTY = "excludejunit5engines";
    private static final String VINTAGE = "junit-vintage";

    private JUnitEngineSelection() {}

    static LauncherDiscoveryRequestBuilder apply(
            LauncherDiscoveryRequestBuilder builder,
            Map<String, String> properties) {
        Set<String> includes = parse(properties == null ? null : properties.get(INCLUDE_PROPERTY));
        Set<String> excludes = parse(properties == null ? null : properties.get(EXCLUDE_PROPERTY));

        if (!includes.isEmpty()) {
            builder.filters(EngineFilter.includeEngines(includes.toArray(String[]::new)));
        }

        // Generic JUnit 4 ownership is not part of P0. Keep the pre-existing safety boundary
        // even when the native provider has Vintage on the classpath; P1 removes this only after
        // native-vs-ScenarioMesh JUnit 4 equivalence is proven.
        excludes.add(VINTAGE);
        builder.filters(EngineFilter.excludeEngines(excludes.toArray(String[]::new)));
        return builder;
    }

    static Set<String> parse(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return values;
        for (String token : raw.split(",", -1)) {
            String value = token.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }
}
