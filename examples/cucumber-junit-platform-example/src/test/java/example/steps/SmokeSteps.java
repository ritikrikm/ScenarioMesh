package example.steps;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SmokeSteps {
    private static final Path LIFECYCLE_LOG = Path.of("target", "cucumber-lifecycle.log");

    @BeforeAll
    public static void beforeAll() throws Exception {
        Files.createDirectories(LIFECYCLE_LOG.getParent());
        Files.writeString(LIFECYCLE_LOG, "BEFORE_ALL\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @AfterAll
    public static void afterAll() throws Exception {
        Files.writeString(LIFECYCLE_LOG, "AFTER_ALL\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Given("a short task")
    public void shortTask() throws Exception {
        Thread.sleep(150);
    }
}
