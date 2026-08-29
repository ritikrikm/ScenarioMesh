package io.scenariomesh.maven.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSelectionFileTest {
    @TempDir Path temp;

    @Test
    void preservesFullSurefireGrammarAndMavenFileLoadingRules() throws Exception {
        Path selectors = temp.resolve("selectors.txt");
        Files.writeString(selectors, String.join("\n",
                "# comment",
                "",
                "**/CheckoutTest.java#happy*",
                "!**/CheckoutTest.java#slow*",
                "%regex[.*ContractTest.*]",
                "  leading-whitespace-is-significant"));

        ExternalSelectionFile.Analysis analysis = ExternalSelectionFile.read(temp, "selectors.txt", "includesFile");

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of(
                "**/CheckoutTest.java#happy*",
                "!**/CheckoutTest.java#slow*",
                "%regex[.*ContractTest.*]",
                "  leading-whitespace-is-significant"), analysis.patterns());
    }

    @Test
    void absolutePathUsesSameFileWithoutProjectRelativeRewrite() throws Exception {
        Path selectors = temp.resolve("absolute.txt").toAbsolutePath();
        Files.writeString(selectors, "**/*IT.java\n");

        ExternalSelectionFile.Analysis analysis = ExternalSelectionFile.read(temp.resolve("other"), selectors.toString(), "includesFile");

        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(List.of("**/*IT.java"), analysis.patterns());
    }
}
