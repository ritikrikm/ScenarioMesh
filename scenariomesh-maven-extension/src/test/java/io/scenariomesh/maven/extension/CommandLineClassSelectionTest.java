package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineClassSelectionTest {

    @Test
    void singleClassSelectorUsesSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", "LoginTest");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue("LoginTest".equals(analysis.testListExpression()));
        assertTrue(matchesAny(analysis, "LoginTest"));
        assertTrue(matchesAny(analysis, "example.LoginTest"));
        assertTrue(matchesClassFile(analysis, "example/LoginTest.class"));
        assertTrue(matchesAny(analysis, "CheckoutTest"));
    }

    @Test
    void wildcardAndMultipleClassSelectorsUseSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "*Login*,CheckoutTest");

        assertTrue(analysis.supported());
        assertTrue("*Login*,CheckoutTest".equals(analysis.testListExpression()));
        assertTrue(matchesAny(analysis, "example.AdminLoginTest"));
        assertTrue(matchesAny(analysis, "another.package.CheckoutTest"));
        assertTrue(matchesAny(analysis, "example.PaymentTest"));
    }

    @Test
    void documentedJavaSuffixUsesSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", "LoginTest.java");

        assertTrue(analysis.supported());
        assertTrue("LoginTest.java".equals(analysis.testListExpression()));
        assertTrue(matchesAny(analysis, "example.LoginTest"));
    }

    @Test
    void delegatesMethodSelectorToSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "LoginTest#successfulLogin");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue(analysis.includeRegexes().stream().anyMatch(regex -> Pattern.matches(regex, "example.LoginTest")));
        assertTrue("LoginTest#successfulLogin".equals(analysis.testListExpression()));
    }

    @Test
    void delegatesNegationToSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze(
                "Failsafe", "it.test", "CheckoutIT,!SlowCheckoutIT");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue("CheckoutIT,!SlowCheckoutIT".equals(analysis.testListExpression()));
    }

    @Test
    void delegatesPackageQualifiedSelectorToSurefirePublicGrammar() {
        var analysis = CommandLineClassSelection.analyze(
                "Surefire", "test", "example.LoginTest");

        assertTrue(analysis.present());
        assertTrue(analysis.supported());
        assertTrue("example.LoginTest".equals(analysis.testListExpression()));
    }

    @Test
    void absentSelectorDoesNotOverrideConfiguredSelection() {
        var analysis = CommandLineClassSelection.analyze("Surefire", "test", null);

        assertFalse(analysis.present());
        assertTrue(analysis.supported());
        assertTrue(analysis.includeRegexes().isEmpty());
        assertNull(analysis.testListExpression());
    }

    private boolean matchesAny(CommandLineClassSelection.Analysis analysis, String dottedClassName) {
        return analysis.includeRegexes().stream().anyMatch(regex -> Pattern.matches(regex, dottedClassName));
    }

    private boolean matchesClassFile(CommandLineClassSelection.Analysis analysis, String classFile) {
        return analysis.includeRegexes().stream().anyMatch(regex -> Pattern.matches(regex, classFile));
    }
}
