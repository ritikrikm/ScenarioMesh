package example;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SuiteSelectionTest {
    @Test(groups = "regression")
    public void suiteXmlGroupMustBeOverriddenByMavenGroup() {
        throw new AssertionError("Maven groups must override the suite group selection");
    }

    @Test(groups = "smoke")
    public void mavenGroupOverridesSuiteXml() throws IOException {
        record("mavenGroupOverridesSuiteXml");
    }

    private static void record(String event) throws IOException {
        Path trace = Path.of(System.getProperty("contract.trace"));
        Files.createDirectories(trace.toAbsolutePath().getParent());
        Files.writeString(trace, event + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
