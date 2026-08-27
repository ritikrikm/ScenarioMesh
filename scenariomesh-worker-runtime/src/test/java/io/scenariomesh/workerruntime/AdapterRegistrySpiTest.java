package io.scenariomesh.workerruntime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdapterRegistrySpiTest {
    @Test
    void loadsExternalAdaptersFromServiceLoaderWithoutCoreRegistration() {
        AdapterRegistry registry = new AdapterRegistry(getClass().getClassLoader());
        assertEquals("external-fixture-framework", registry.required("external-fixture").framework());
    }
}
