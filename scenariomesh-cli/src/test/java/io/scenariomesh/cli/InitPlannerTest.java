package io.scenariomesh.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitPlannerTest {
    @TempDir Path temp;

    private final InitPlanner planner = new InitPlanner();
    private final InitApplier applier = new InitApplier();

    @Test
    void cleanMavenProjectCreatesExtensionAndMinimalConfigAndThenBecomesIdempotent() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");

        InitPlan first = planner.plan(temp, "1.2.3");

        assertEquals(2, first.changes().size());
        assertTrue(first.changes().stream().anyMatch(change -> change.path().endsWith(".mvn/extensions.xml")));
        assertTrue(first.changes().stream().anyMatch(change -> change.path().endsWith("scenariomesh.yml")));

        applier.apply(first);
        InitPlan second = planner.plan(temp, "1.2.3");
        assertTrue(second.empty());
    }

    @Test
    void preservesExistingMavenExtensionsAndAddsScenarioMeshExactlyOnce() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path mvn = Files.createDirectories(temp.resolve(".mvn"));
        Files.writeString(mvn.resolve("extensions.xml"), """
                <extensions>
                  <extension>
                    <groupId>com.example</groupId>
                    <artifactId>company-extension</artifactId>
                    <version>9</version>
                  </extension>
                </extensions>
                """);

        InitPlan plan = planner.plan(temp, "1.2.3");
        applier.apply(plan);
        String xml = Files.readString(mvn.resolve("extensions.xml"));

        assertTrue(xml.contains("company-extension"));
        assertTrue(xml.contains("scenariomesh-maven-extension"));
        assertEquals(1, occurrences(xml, "scenariomesh-maven-extension"));
    }

    @Test
    void updatesExistingScenarioMeshExtensionVersionWithoutDuplicatingIt() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path mvn = Files.createDirectories(temp.resolve(".mvn"));
        Files.writeString(mvn.resolve("extensions.xml"), """
                <extensions><extension>
                  <groupId>io.scenariomesh</groupId>
                  <artifactId>scenariomesh-maven-extension</artifactId>
                  <version>0.9.0</version>
                </extension></extensions>
                """);

        InitPlan plan = planner.plan(temp, "1.2.3");
        applier.apply(plan);
        String xml = Files.readString(mvn.resolve("extensions.xml"));

        assertTrue(xml.contains("1.2.3"));
        assertFalse(xml.contains("0.9.0"));
        assertEquals(1, occurrences(xml, "scenariomesh-maven-extension"));
    }

    @Test
    void malformedExistingExtensionsFileFailsBeforeAnyConfigIsCreated() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path mvn = Files.createDirectories(temp.resolve(".mvn"));
        Files.writeString(mvn.resolve("extensions.xml"), "<extensions><broken></extensions>");

        assertThrows(IllegalArgumentException.class, () -> planner.plan(temp, "1.2.3"));
        assertFalse(Files.exists(temp.resolve("scenariomesh.yml")));
    }

    @Test
    void nonMavenDirectoryFailsWithoutWritingAnything() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(temp, "1.2.3"));
        assertFalse(Files.exists(temp.resolve(".mvn")));
        assertFalse(Files.exists(temp.resolve("scenariomesh.yml")));
    }

    @Test
    void existingYamlIsPreservedAndNotRewritten() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        String custom = "scenariomesh:\n  configVersion: 1\n  workers:\n    count: 2\n";
        Files.writeString(temp.resolve("scenariomesh.yml"), custom);

        InitPlan plan = planner.plan(temp, "1.2.3");
        applier.apply(plan);

        assertEquals(custom, Files.readString(temp.resolve("scenariomesh.yml")));
    }

    @Test
    void bothYamlNamesFailClosed() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.writeString(temp.resolve("scenariomesh.yml"), "scenariomesh:\n  configVersion: 1\n");
        Files.writeString(temp.resolve("scenariomesh.yaml"), "scenariomesh:\n  configVersion: 1\n");

        assertThrows(IllegalArgumentException.class, () -> planner.plan(temp, "1.2.3"));
    }

    @Test
    void projectPathWithSpacesIsHandledNormally() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project with spaces"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        InitPlan plan = planner.plan(project, "1.2.3");
        applier.apply(plan);

        assertTrue(Files.isRegularFile(project.resolve(".mvn/extensions.xml")));
        assertTrue(Files.isRegularFile(project.resolve("scenariomesh.yml")));
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }
}
