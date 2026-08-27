package io.scenariomesh.workerruntime;

import io.scenariomesh.protocol.Protocol.WorkerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerCapabilitiesTest {
    @Test
    void advertisesRuntimeAdaptersAndJUnitPlatformEngines() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        WorkerCapabilities capabilities = WorkerMain.capabilities(new AdapterRegistry(classLoader), classLoader);

        assertTrue(capabilities.adapterIds().contains("junit-platform"));
        assertTrue(capabilities.engineIds().contains("junit-jupiter"));
    }
}
