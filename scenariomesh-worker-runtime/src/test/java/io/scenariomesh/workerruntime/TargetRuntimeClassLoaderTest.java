package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Ports.ScenarioAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRuntimeClassLoaderTest {
    @Test
    void adaptersAreChildOwnedWhileCoreContractIsShared() throws Exception {
        ClassLoader parent = getClass().getClassLoader();
        try (TargetRuntimeClassLoader target = TargetRuntimeClassLoader.fromCurrentClasspath(parent)) {
            Class<?> adapter = Class.forName(
                    "io.scenariomesh.adapter.junitplatform.JUnitPlatformAdapter", true, target);
            Class<?> coreContract = Class.forName(
                    "io.scenariomesh.core.Ports$ScenarioAdapter", true, target);

            assertSame(ScenarioAdapter.class, coreContract);
            assertNotSame(parent.loadClass(adapter.getName()), adapter);
            assertTrue(ScenarioAdapter.class.isAssignableFrom(adapter));
        }
    }
}
