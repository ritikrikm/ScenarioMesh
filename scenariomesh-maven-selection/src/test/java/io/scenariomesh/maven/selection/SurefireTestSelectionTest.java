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
    void classOnlyPatternUsesSameResolver() {
        SurefireTestSelection selection = new SurefireTestSelection("*ContractTest");
        assertFalse(selection.hasMethodPatterns());
        assertTrue(selection.mayContainSelectedMethod("example.AlphaContractTest"));
        assertTrue(selection.matches("example.AlphaContractTest", "alpha"));
        assertFalse(selection.matches("example.OtherTest", "alpha"));
    }
}
