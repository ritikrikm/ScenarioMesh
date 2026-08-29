package example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BetaContractTest {
    @BeforeAll
    static void beforeAll() {
        ContractRecorder.record("BetaContractTest#BEFORE_ALL");
    }

    @AfterAll
    static void afterAll() {
        ContractRecorder.record("BetaContractTest#AFTER_ALL");
    }

    @Test
    void betaOne() {
        ContractRecorder.record("BetaContractTest#betaOne");
    }
}
