package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class NotSelectedTest {
    @Test
    void mustNotRunWhenSurefireIncludesArePreserved() {
        fail("This test proves ScenarioMesh must pass through when Surefire selection is customized");
    }
}
