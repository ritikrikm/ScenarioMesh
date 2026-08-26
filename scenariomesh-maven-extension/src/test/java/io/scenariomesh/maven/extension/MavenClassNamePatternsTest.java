package io.scenariomesh.maven.extension;

import org.apache.maven.surefire.api.testset.TestListResolver;
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
    void globSelectionStillSupportsNestedPackagesAndQuestionWildcards() {
        String nested = MavenClassNamePatterns.toRegex("**/api/?oginIT.java");

        assertTrue(Pattern.matches(nested, "com/acme/api/LoginIT"));
        assertTrue(Pattern.matches(nested, "com.acme.api.LoginIT"));
        assertFalse(Pattern.matches(nested, "com/acme/api/LongLoginIT"));
    }

    @Test
    void supportedSelectorMatrixMatchesNativeSurefireTestListResolver() {
        List<String> selectors = List.of(
                "**/Test*.java",
                "**/*Test.java",
                "**/*Tests.java",
                "**/*TestCase.java",
                "**/api/?oginIT.java",
                "%regex[com/acme/.*IT.class]",
                "%regex[.*(Cat|Dog).*Test.*]");
        List<String> classFiles = List.of(
                "TestSmoke.class",
                "com/acme/TestPayment.class",
                "com/acme/PaymentTest.class",
                "com/acme/PaymentTests.class",
                "com/acme/PaymentTestCase.class",
                "com/acme/PaymentHelper.class",
                "com/acme/api/LoginIT.class",
                "com/acme/api/LongLoginIT.class",
                "com/acme/CatFastTest.class",
                "com/acme/DogSlowTest.class",
                "other/pkg/LoginIT.class");

        for (String selector : selectors) {
            String meshRegex = MavenClassNamePatterns.toRegex(selector);
            TestListResolver surefire = new TestListResolver(List.of(selector), List.of());
            for (String classFile : classFiles) {
                boolean expected = surefire.shouldRun(classFile, null);
                boolean actual = Pattern.matches(meshRegex, classFile);
                assertEquals(expected, actual,
                        () -> "ScenarioMesh selector diverged from Surefire for selector=" + selector
                                + ", classFile=" + classFile);
            }
        }
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
}
