package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenClassNamePatternsTest {

    @Test
    void mavenRegexMatchesForwardSlashClassFilePathsOnly() {
        String regex = MavenClassNamePatterns.toRegex("%regex[com/acme/.*IT.class]");

        assertTrue(Pattern.matches(regex, "com/acme/LoginIT.class"));
        assertFalse(Pattern.matches(regex, "com.acme.LoginIT"));
        assertFalse(Pattern.matches(regex, "com/acme/LoginIT.java"));
    }

    @Test
    void mavenRegexDoesNotAccidentallyGainDottedClassNameSemantics() {
        String regex = MavenClassNamePatterns.toRegex("%regex[com\\.acme\\..*IT.class]");

        assertFalse(Pattern.matches(regex, "com/acme/LoginIT.class"));
        assertFalse(Pattern.matches(regex, "com.acme.LoginIT"));
    }

    @Test
    void commaInsideMavenRegexIsNotSplitIntoMultipleSelectors() {
        var analysis = MavenClassNamePatterns.analyze(List.of("%regex[com/acme/(Smoke,Fast).*IT.class]"));

        assertTrue(analysis.supported(), () -> String.join("; ", analysis.unsupportedReasons()));
        assertEquals(1, analysis.patterns().size());
    }

    @Test
    void documentedDefaultSurefirePatternsMatchClassFilesAndDottedNames() {
        assertMatches("**/Test*.java", "com/acme/TestPayment.class", "com.acme.TestPayment");
        assertMatches("**/*Test.java", "com/acme/PaymentTest.class", "com.acme.PaymentTest");
        assertMatches("**/*Tests.java", "com/acme/PaymentTests.class", "com.acme.PaymentTests");
        assertMatches("**/*TestCase.java", "com/acme/PaymentTestCase.class", "com.acme.PaymentTestCase");

        String testPattern = MavenClassNamePatterns.toRegex("**/*Test.java");
        assertFalse(Pattern.matches(testPattern, "com/acme/PaymentHelper.class"));
        assertFalse(Pattern.matches(testPattern, "com.acme.PaymentHelper"));
    }

    @Test
    void globSelectionSupportsNestedPackagesAndQuestionWildcardsOnBothRepresentations() {
        String nested = MavenClassNamePatterns.toRegex("**/api/?oginIT.java");

        assertTrue(Pattern.matches(nested, "com/acme/api/LoginIT.class"));
        assertTrue(Pattern.matches(nested, "com.acme.api.LoginIT"));
        assertFalse(Pattern.matches(nested, "com/acme/api/LongLoginIT.class"));
    }

    @Test
    void malformedOrInvalidMavenRegexFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenClassNamePatterns.toRegex("%regex["));
        assertThrows(IllegalArgumentException.class,
                () -> MavenClassNamePatterns.toRegex("%regex[(*.class]"));
    }

    @Test
    void unsupportedMethodAndInlineNegationSelectorsStillFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenClassNamePatterns.toRegex("com.acme.LoginTest#works"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenClassNamePatterns.toRegex("!**/*SlowTest.java"));
    }

    private void assertMatches(String selector, String classFile, String dottedClass) {
        String regex = MavenClassNamePatterns.toRegex(selector);
        assertTrue(Pattern.matches(regex, classFile), () -> selector + " should match " + classFile);
        assertTrue(Pattern.matches(regex, dottedClass), () -> selector + " should match " + dottedClass);
    }
}
