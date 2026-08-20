package io.scenariomesh.adapter.cucumberjunit4;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.core.Ports.AdapterContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CucumberJUnit4OwnershipTest {
    @Test
    void ordinaryJUnit4ClassCannotBeSilentlyOmittedDuringCucumberTakeover() throws Exception {
        Path testClasses = Path.of(HiddenLegacyTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        AdapterContext context = new AdapterContext(
                getClass().getClassLoader(),
                List.of(testClasses),
                Map.of(),
                new DiscoverySelection(
                        List.of(".*HiddenLegacyTest"),
                        List.of()));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CucumberJUnit4Adapter().discover(context));

        assertTrue(failure.getMessage().contains("HiddenLegacyTest"), failure.getMessage());
        assertTrue(failure.getMessage().contains("will not silently omit"), failure.getMessage());
    }
}
