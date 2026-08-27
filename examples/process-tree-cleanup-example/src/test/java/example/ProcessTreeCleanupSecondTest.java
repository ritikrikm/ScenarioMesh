package example;

import org.junit.jupiter.api.Test;

class ProcessTreeCleanupSecondTest {
    @Test
    void independentScopeTwo() throws Exception {
        ProcessTreeProbe.spawnOrVerifyRetiredChild();
    }
}
