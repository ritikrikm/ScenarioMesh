package io.scenariomesh.maven.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireTestSelectionTest {
    @Test
    void selectsOneMethodWithoutSelectingSiblingMethod() {
        SurefireTestSelection selection = new SurefireTestSelection("LoginTest#smoke");
        assertTrue(selection.hasMethodPatterns());
        assertTrue(selection.mayContainSelectedMethod("example.LoginTest"));
        assertTrue(selection.matches("example.LoginTest", "smoke"));
        assertFalse(selection.matches("example.LoginTest", "regression"));
        assertFalse(selection.matches("example.OtherTest", "smoke"));
    }

    @Test
    void supportsMethodWildcardsAndCommaUnions() {
        SurefireTestSelection selection = new SurefireTestSelection("LoginTest#smoke*,CheckoutTest#pay?");
        assertTrue(selection.matches("example.LoginTest", "smokeChrome"));
        assertTrue(selection.matches("example.CheckoutTest", "pay1"));
        assertFalse(selection.matches("example.CheckoutTest", "pay12"));
    }

    @Test
    void supportsNegatedClassAndMethodPatterns() {
        SurefireTestSelection selection = new SurefireTestSelection("*Test,!SlowTest,!LoginTest#flaky*");
        assertTrue(selection.matches("example.LoginTest", "smoke"));
        assertFalse(selection.matches("example.LoginTest", "flakyNetwork"));
        assertFalse(selection.matches("example.SlowTest", "smoke"));
    }

    @Test
    void supportsPlusSeparatedMethodUnionAndClassOptionalMethodPatterns() {
        SurefireTestSelection union = new SurefireTestSelection("LoginTest#smoke+regression????");
        assertTrue(union.matches("example.LoginTest", "smoke"));
        assertTrue(union.matches("example.LoginTest", "regressionFast"));
        assertFalse(union.matches("example.LoginTest", "other"));

        SurefireTestSelection classOptional = new SurefireTestSelection("#fast*+slowTest");
        assertTrue(classOptional.mayContainSelectedMethod("example.AnyTest"));
        assertTrue(classOptional.matches("example.AnyTest", "fastCheckout"));
        assertTrue(classOptional.matches("example.OtherTest", "slowTest"));
        assertFalse(classOptional.matches("example.OtherTest", "unrelated"));
    }

    @Test
    void supportsRegexClassAndMethodSelection() {
        SurefireTestSelection selection = new SurefireTestSelection(
                "%regex[.*.LoginTest.class#(smoke.*|checkout)]");
        assertTrue(selection.matches("example.LoginTest", "smokeChrome"));
        assertTrue(selection.matches("example.LoginTest", "checkout"));
        assertFalse(selection.matches("example.LoginTest", "regression"));
        assertFalse(selection.matches("example.OtherTest", "checkout"));
    }

    @Test
    void supportsParameterizedJUnit4DescriptionNamesDocumentedBySurefire() {
        SurefireTestSelection allInvocations = new SurefireTestSelection("ParameterizedTest#testMethod[*]");
        assertTrue(allInvocations.matches("example.ParameterizedTest", "testMethod[0]"));
        assertTrue(allInvocations.matches("example.ParameterizedTest", "testMethod[5: fib(5)=5]"));

        SurefireTestSelection oneInvocation = new SurefireTestSelection("ParameterizedTest#testMethod[5:*]");
        assertTrue(oneInvocation.matches("example.ParameterizedTest", "testMethod[5: fib(5)=5]"));
        assertFalse(oneInvocation.matches("example.ParameterizedTest", "testMethod[4: fib(4)=3]"));
    }

    @Test
    void classOnlyPatternUsesSameResolver() {
        SurefireTestSelection selection = new SurefireTestSelection("*ContractTest");
        assertFalse(selection.hasMethodPatterns());
        assertTrue(selection.mayContainSelectedMethod("example.AlphaContractTest"));
        assertTrue(selection.matches("example.AlphaContractTest", "alpha"));
        assertFalse(selection.matches("example.OtherTest", "alpha"));
    }
}
