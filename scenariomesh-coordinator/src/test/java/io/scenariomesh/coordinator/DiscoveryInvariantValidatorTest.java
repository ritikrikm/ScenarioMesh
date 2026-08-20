package io.scenariomesh.coordinator;

import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryInvariantValidatorTest {
    private final DiscoveryInvariantValidator validator = new DiscoveryInvariantValidator();

    @Test
    void uniqueIdsAndSelectorsAreAccepted() {
        assertDoesNotThrow(() -> validator.validate(
                List.of("junit-platform"),
                List.of(
                        task("id-1", "selector-1"),
                        task("id-2", "selector-2"))));
    }

    @Test
    void duplicateScenarioIdsFailBeforeWorkersStart() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> validator.validate(
                List.of("junit-platform"),
                List.of(
                        task("same-id", "selector-1"),
                        task("same-id", "selector-2"))));

        assertTrue(failure.getMessage().contains("duplicate ScenarioTask id 'same-id'"), failure.getMessage());
    }

    @Test
    void duplicateAdapterSelectorFailsEvenWhenIdsDiffer() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> validator.validate(
                List.of("junit-platform"),
                List.of(
                        task("id-1", "same-selector"),
                        task("id-2", "same-selector"))));

        assertTrue(failure.getMessage().contains("duplicate selector"), failure.getMessage());
        assertTrue(failure.getMessage().contains("same-selector"), failure.getMessage());
    }

    @Test
    void taskCannotClaimAnAdapterThatWasNotSelected() {
        ScenarioTask wrongAdapter = new ScenarioTask(
                new ScenarioId("id-1"), "id-1", "testng", "testng",
                null, null, "selector", Set.of(), Map.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> validator.validate(
                List.of("junit-platform"), List.of(wrongAdapter)));

        assertTrue(failure.getMessage().contains("unselected adapter 'testng'"), failure.getMessage());
    }

    private ScenarioTask task(String id, String selector) {
        return new ScenarioTask(
                new ScenarioId(id), id, "junit-platform", "junit5",
                null, null, selector, Set.of(), Map.of());
    }
}
