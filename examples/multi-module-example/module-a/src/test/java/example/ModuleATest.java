package example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleATest {
    @Test void moduleAOne() { assertEquals(2, 1 + 1); }
    @Test void moduleATwo() { assertEquals("a", "a"); }
}
