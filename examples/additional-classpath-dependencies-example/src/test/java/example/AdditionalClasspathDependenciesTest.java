package example;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalClasspathDependenciesTest {
    @Test
    void additionalDependencyAndItsTransitiveAreVisibleWithoutProjectConflictMediation() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class.forName("org.apache.commons.text.StringSubstitutor", true, loader);
        Class.forName("org.apache.commons.lang3.StringUtils", true, loader);

        List<String> langMetadata = Collections.list(
                        loader.getResources("META-INF/maven/org.apache.commons/commons-lang3/pom.properties"))
                .stream().map(URL::toString).toList();
        List<String> textMetadata = Collections.list(
                        loader.getResources("META-INF/maven/org.apache.commons/commons-text/pom.properties"))
                .stream().map(URL::toString).toList();

        assertTrue(langMetadata.stream().anyMatch(value -> value.contains("commons-lang3-3.12.0.jar")), langMetadata.toString());
        assertTrue(langMetadata.stream().anyMatch(value -> value.contains("commons-lang3-3.14.0.jar")), langMetadata.toString());
        assertTrue(textMetadata.stream().anyMatch(value -> value.contains("commons-text-1.12.0.jar")), textMetadata.toString());
    }
}
