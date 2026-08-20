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
    void legacyOptionsAppendAnIsolatedJsonPluginWithoutDroppingExistingOptions() {
        Path output = Path.of("target", "cucumber-report", "isolated.json");

        String value = CucumberJsonReportIsolation.legacyOptionsValue(
                "--tags @smoke --monochrome",
                output);

        assertEquals(
                "--tags @smoke --monochrome --plugin json:" + output.toAbsolutePath().normalize(),
                value);
    }
}
