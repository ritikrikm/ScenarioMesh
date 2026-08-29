package io.scenariomesh.adapter.junitplatform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitEngineSelectionTest {
    @Test
    void parsesSurefireProviderEngineLists() {
        assertEquals(
                java.util.Set.of("junit-jupiter", "cucumber"),
                JUnitEngineSelection.parse("junit-jupiter, cucumber"));
    }

    @Test
    void emptyEnginePropertyIsNoSelection() {
        assertTrue(JUnitEngineSelection.parse(Map.<String, String>of().get("missing")).isEmpty());
    }
}
