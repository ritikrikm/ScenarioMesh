package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Ports.ScenarioAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRuntimeClassLoaderTest {
    @Test
    void adaptersAndTargetLibrariesAreChildOwnedWhileCoreContractIsShared() throws Exception {
        ClassLoader parent = getClass().getClassLoader();
        try (TargetRuntimeClassLoader target = TargetRuntimeClassLoader.fromCurrentClasspath(parent)) {
            Class<?> adapter = Class.forName(
                    "io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter", true, target);
            Class<?> coreContract = Class.forName(
                    "io.scenariomesh.core.Ports$ScenarioAdapter", true, target);
            Class<?> targetJackson = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, target);

            assertSame(ScenarioAdapter.class, coreContract);
            assertNotSame(parent.loadClass(adapter.getName()), adapter);
            assertNotSame(parent.loadClass("com.fasterxml.jackson.databind.ObjectMapper"), targetJackson);
            assertTrue(ScenarioAdapter.class.isAssignableFrom(adapter));
        }
    }
}
