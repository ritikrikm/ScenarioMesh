package io.scenariomesh.adapter.cucumberjunit4;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CucumberJsonReportIsolationTest {

    @Test
    void modernPluginValuePreservesNonJsonPluginsAndReplacesSharedJsonOutput() {
        Path output = Path.of("target", "cucumber-report", "scenariomesh-worker-1-task.json");

        String value = CucumberJsonReportIsolation.modernPluginValue(
                List.of("pretty", "json:target/shared.json", "rerun:target/rerun.txt"),
                "summary,json:target/other.json",
                output);

        assertTrue(value.contains("pretty"));
        assertTrue(value.contains("rerun:target/rerun.txt"));
        assertTrue(value.contains("summary"));
        assertTrue(value.contains("json:" + output.toAbsolutePath().normalize()));
        assertFalse(value.contains("target/shared.json"));
        assertFalse(value.contains("target/other.json"));
    }

    @Test
    void arbitraryCustomEventAndReportingPluginsArePreservedExactlyOnce() {
        Path output = Path.of("target", "cucumber-report", "isolated.json");
        String customListener = "com.acme.testing.CustomTestEventListener";
        String externalReporter = "com.vendor.reporting.ScenarioReporter";

        String value = CucumberJsonReportIsolation.modernPluginValue(
                List.of(customListener, "pretty", "json:target/native.json"),
                externalReporter + "," + customListener,
                output);

        assertEquals(1, occurrences(value, customListener));
        assertEquals(1, occurrences(value, externalReporter));
        assertTrue(value.contains("pretty"));
        assertTrue(value.contains("json:" + output.toAbsolutePath().normalize()));
        assertFalse(value.contains("target/native.json"));
    }

    @Test
    void legacyOptionsAppendAnIsolatedJsonPluginWithoutDroppingExistingOptions() {
        Path output = Path.of("target", "cucumber-report", "isolated.json");

        String value = CucumberJsonReportIsolation.legacyOptionsValue(
                "--tags @smoke --plugin com.acme.CustomListener --monochrome",
                output);

        assertTrue(value.contains("--tags @smoke"));
        assertTrue(value.contains("--plugin com.acme.CustomListener"));
        assertTrue(value.contains("--monochrome"));
        assertTrue(value.endsWith("--plugin json:" + output.toAbsolutePath().normalize()));
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
