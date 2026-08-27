package example;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerClassLifecycleTest {
    private int mutableState;

    @BeforeAll
    void beforeAll() throws IOException {
        mutableState = 10;
        Path log = Path.of("target", "per-class-lifecycle.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "BEFORE_ALL\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    @Order(1)
    void firstMutatesSharedInstance() {
        assertEquals(10, mutableState);
        mutableState = 42;
    }

    @Test
    @Order(2)
    void secondObservesSameInstance() {
        assertEquals(42, mutableState);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @Order(3)
    void parameterizedInvocationsRemainInSameScope(int value) {
        assertEquals(42, mutableState);
        assertTrue(value > 0);
    }
}
