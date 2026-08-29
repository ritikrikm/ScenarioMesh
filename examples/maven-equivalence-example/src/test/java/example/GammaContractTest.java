package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class GammaContractTest {
    @Test
    void excludedBySelectionFile() {
        fail("excludesFile was not applied");
    }
}
