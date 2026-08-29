package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineClassSelectionTest {

    @Test
    void acceptsSingleClassSelector() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", "LoginTest");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue(matchesAny(analysis, "LoginTest"));
        assertFalse(matchesAny(analysis, "CheckoutTest"));
    }

    @Test
    void acceptsWildcardAndMultipleClassSelectors() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "*Login*,CheckoutTest");

        assertTrue(analysis.supported());
        assertTrue(matchesAny(analysis, "AdminLoginTest"));
        assertTrue(matchesAny(analysis, "CheckoutTest"));
        assertFalse(matchesAny(analysis, "PaymentTest"));
    }

    @Test
    void rejectsMethodSelectorUntilCompleteGrammarIsOwned() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "LoginTest#successfulLogin");

        assertTrue(analysis.present());
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("method selectors"));
    }

    @Test
    void rejectsNegationUntilCompleteGrammarIsOwned() {
        var analysis = CommandLineClassSelection.analyze(
                "Failsafe", "it.test", "CheckoutIT,!SlowCheckoutIT");

        assertTrue(analysis.present());
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("inline negation"));
    }

    @Test
    void absentSelectorDoesNotOverrideConfiguredSelection() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", null);

        assertFalse(analysis.present());
        assertTrue(analysis.supported());
        assertTrue(analysis.includeRegexes().isEmpty());
    }

    private boolean matchesAny(CommandLineClassSelection.Analysis analysis, String dottedClassName) {
        return analysis.includeRegexes().stream().anyMatch(regex -> Pattern.matches(regex, dottedClassName));
    }
}
