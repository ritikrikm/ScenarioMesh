package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineClassSelectionTest {

    @Test
    void acceptsSingleClassSelectorAcrossPackages() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", "LoginTest");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue(matchesAny(analysis, "LoginTest"));
        assertTrue(matchesAny(analysis, "example.LoginTest"));
        assertTrue(matchesClassFile(analysis, "example/LoginTest.class"));
        assertFalse(matchesAny(analysis, "CheckoutTest"));
    }

    @Test
    void acceptsWildcardAndMultipleClassSelectorsAcrossPackages() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "*Login*,CheckoutTest");

        assertTrue(analysis.supported());
        assertTrue(matchesAny(analysis, "example.AdminLoginTest"));
        assertTrue(matchesAny(analysis, "another.package.CheckoutTest"));
        assertFalse(matchesAny(analysis, "example.PaymentTest"));
    }

    @Test
    void acceptsDocumentedJavaSuffixWithoutMakingItPackageQualified() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", "LoginTest.java");

        assertTrue(analysis.supported());
        assertTrue(matchesAny(analysis, "example.LoginTest"));
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
    void rejectsPackageQualifiedSelectorUntilFullGrammarIsOwned() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "example.LoginTest");

        assertTrue(analysis.present());
        assertFalse(analysis.supported());
        assertTrue(analysis.reason().contains("package-qualified"));
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

    private boolean matchesClassFile(CommandLineClassSelection.Analysis analysis, String classFile) {
        return analysis.includeRegexes().stream().anyMatch(regex -> Pattern.matches(regex, classFile));
    }
}
