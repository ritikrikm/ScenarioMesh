package io.scenariomesh.adapter.junitplatform;

import org.junit.platform.launcher.TestIdentifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges JUnit Platform leaf descriptors conservatively.
 *
 * <p>JUnit Platform can expose the same Cucumber executable twice when a
 * classpath-root request discovers both the Cucumber engine directly and a
 * JUnit Platform @Suite that delegates to that engine. Those descriptors have
 * different full UniqueIds even though the Cucumber-engine suffix is the same.
 *
 * <p>This merger removes only the provable direct-vs-suite overlap. Two
 * suite-owned descriptors are intentionally retained because separate suites
 * may carry different execution semantics. Non-Cucumber descriptors are
 * identified by their complete UniqueId and are never cross-context merged.
 */
final class DiscoveredExecutionMerger {
    private static final String CUCUMBER_ENGINE_SEGMENT = "[engine:cucumber]";
    private static final String SUITE_ENGINE_SEGMENT = "[engine:junit-platform-suite]";

    List<TestIdentifier> merge(List<TestIdentifier> leaves) {
        Map<String, List<TestIdentifier>> cucumberGroups = new LinkedHashMap<>();
        Map<String, TestIdentifier> ordinary = new LinkedHashMap<>();

        for (TestIdentifier leaf : leaves) {
            String uniqueId = leaf.getUniqueId();
            Optional<String> cucumberIdentity = cucumberIdentity(uniqueId);
            if (cucumberIdentity.isEmpty()) {
                ordinary.putIfAbsent(uniqueId, leaf);
                continue;
            }
            cucumberGroups.computeIfAbsent(cucumberIdentity.get(), ignored -> new ArrayList<>())
                    .add(leaf);
        }

        List<TestIdentifier> merged = new ArrayList<>(ordinary.values());
        for (List<TestIdentifier> group : cucumberGroups.values()) {
            merged.addAll(mergeCucumberGroup(group));
        }
        return List.copyOf(merged);
    }

    private List<TestIdentifier> mergeCucumberGroup(List<TestIdentifier> group) {
        List<TestIdentifier> suiteOwned = group.stream()
                .filter(identifier -> isSuiteOwned(identifier.getUniqueId()))
                .toList();

        if (!suiteOwned.isEmpty()) {
            // A direct descriptor and a suite-owned descriptor with the same
            // Cucumber suffix are the same executable reached through two
            // discovery paths. Prefer suite ownership because it preserves the
            // target repository's explicit selector semantics.
            return distinctByFullUniqueId(suiteOwned);
        }
        return distinctByFullUniqueId(group);
    }

    private List<TestIdentifier> distinctByFullUniqueId(List<TestIdentifier> identifiers) {
        Map<String, TestIdentifier> distinct = new LinkedHashMap<>();
        for (TestIdentifier identifier : identifiers) {
            distinct.putIfAbsent(identifier.getUniqueId(), identifier);
        }
        return List.copyOf(distinct.values());
    }

    static Optional<String> cucumberIdentity(String uniqueId) {
        int cucumberStart = uniqueId.indexOf(CUCUMBER_ENGINE_SEGMENT);
        if (cucumberStart < 0) {
            return Optional.empty();
        }
        return Optional.of(uniqueId.substring(cucumberStart));
    }

    private boolean isSuiteOwned(String uniqueId) {
        int cucumberStart = uniqueId.indexOf(CUCUMBER_ENGINE_SEGMENT);
        return cucumberStart > 0 && uniqueId.substring(0, cucumberStart).contains(SUITE_ENGINE_SEGMENT);
    }
}
