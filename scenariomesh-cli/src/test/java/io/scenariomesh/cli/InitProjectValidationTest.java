package io.scenariomesh.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitProjectValidationTest {
    @TempDir Path temp;

    @Test
    void malformedPomFailsBeforeAnyScenarioMeshFilesArePlannedOrWritten() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project><broken></project>");

        assertThrows(IllegalArgumentException.class,
                () -> new InitPlanner().plan(temp, "1.2.3"));

        assertFalse(Files.exists(temp.resolve(".mvn")));
        assertFalse(Files.exists(temp.resolve("scenariomesh.yml")));
    }

    @Test
    void nonProjectXmlRootFailsClosed() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<not-a-project/>");

        assertThrows(IllegalArgumentException.class,
                () -> new InitPlanner().plan(temp, "1.2.3"));

        assertFalse(Files.exists(temp.resolve(".mvn")));
    }
}
