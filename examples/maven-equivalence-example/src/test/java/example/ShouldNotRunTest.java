package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class ShouldNotRunTest {
    @Test
    void shouldNeverRunWhenCommandSelectorOverridesConfiguredIncludes() {
        fail("Configured includes were not overridden by the Maven command selector");
    }
}
