package example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AlphaContractTest {
    @BeforeAll
    static void beforeAll() {
        ContractRecorder.record("AlphaContractTest#BEFORE_ALL");
    }

    @AfterAll
    static void afterAll() {
        ContractRecorder.record("AlphaContractTest#AFTER_ALL");
    }

    @Test
    void alphaOne() {
        ContractRecorder.record("AlphaContractTest#alphaOne");
    }

    @Test
    void alphaTwo() {
        ContractRecorder.record("AlphaContractTest#alphaTwo");
    }
}
