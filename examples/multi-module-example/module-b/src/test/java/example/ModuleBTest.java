package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBTest {
    @Test void moduleBOne() { assertTrue(true); }
    @Test void moduleBTwo() { assertTrue("reactor".startsWith("react")); }
}
