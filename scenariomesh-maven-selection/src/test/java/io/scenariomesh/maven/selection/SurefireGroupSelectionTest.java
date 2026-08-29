package io.scenariomesh.maven.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireGroupSelectionTest {
    @Test
    void commaAndLogicalOrUseSurefireGrammar() {
        var selection = SurefireGroupSelection.fromExpressions("smoke | api", null);
        assertTrue(selection.matches("smoke"));
        assertTrue(selection.matches("api"));
        assertFalse(selection.matches("regression"));
    }

    @Test
    void excludedExpressionVetoesIncludedExpression() {
        var selection = SurefireGroupSelection.fromExpressions("smoke, regression", "regression");
        assertTrue(selection.matches("smoke"));
        assertFalse(selection.matches("regression"));
    }

    @Test
    void noExpressionsSelectEverything() {
        var selection = SurefireGroupSelection.fromExpressions(null, null);
        assertTrue(selection.matches());
        assertTrue(selection.matches("anything"));
    }

    @Test
    void invalidExpressionFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> SurefireGroupSelection.fromExpressions("smoke &&", null));
    }
}
