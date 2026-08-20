package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ScenarioTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinator-owned safety boundary for discovered work. Adapter-local de-duplication
 * is an optimization only; correctness must not depend on every adapter implementing it.
 */
final class DiscoveryInvariantValidator {
    void validate(List<String> selectedAdapters, List<ScenarioTask> tasks) {
        Set<String> adapterIds = new HashSet<>(selectedAdapters);
        Map<String, Integer> idCounts = new HashMap<>();
        Map<String, Integer> selectorCounts = new HashMap<>();
        List<String> violations = new ArrayList<>();

        if (adapterIds.size() != selectedAdapters.size()) {
            violations.add("discovery selected duplicate adapter ids");
        }

        for (ScenarioTask task : tasks) {
            String id = task.id().value();
            idCounts.merge(id, 1, Integer::sum);
            selectorCounts.merge(task.adapterId() + "\u0000" + task.selector(), 1, Integer::sum);

            if (!adapterIds.contains(task.adapterId())) {
                violations.add("task '" + id + "' references unselected adapter '" + task.adapterId() + "'");
            }
            if (task.selector().isBlank()) {
                violations.add("task '" + id + "' has a blank selector");
            }
            if (task.displayName().isBlank()) {
                violations.add("task '" + id + "' has a blank display name");
            }
        }

        idCounts.forEach((id, count) -> {
            if (count > 1) {
                violations.add("duplicate ScenarioTask id '" + id + "' appears " + count + " times");
            }
        });
        selectorCounts.forEach((key, count) -> {
            if (count > 1) {
                int split = key.indexOf('\u0000');
                String adapter = key.substring(0, split);
                String selector = key.substring(split + 1);
                violations.add("duplicate selector for adapter '" + adapter + "': '" + selector
                        + "' appears " + count + " times");
            }
        });

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "ScenarioMesh discovery invariant violation(s): " + String.join("; ", violations));
        }
    }
}
