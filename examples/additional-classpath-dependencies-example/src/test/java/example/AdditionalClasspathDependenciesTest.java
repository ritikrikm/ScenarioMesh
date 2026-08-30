package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalClasspathDependenciesTest {
    @Test
    void additionalDependencyAndItsTransitiveAreVisibleWithoutProjectConflictMediation() throws Exception {
        Class.forName("org.apache.commons.text.StringSubstitutor");
        Class.forName("org.apache.commons.lang3.StringUtils");

        String classpath = System.getProperty("java.class.path", "");
        assertTrue(classpath.contains("commons-lang3-3.12.0.jar"), classpath);
        assertTrue(classpath.contains("commons-lang3-3.14.0.jar"), classpath);
        assertTrue(classpath.contains("commons-text-1.12.0.jar"), classpath);
    }
}
