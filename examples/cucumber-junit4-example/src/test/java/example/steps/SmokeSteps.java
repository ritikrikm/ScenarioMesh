package example.steps;

import io.cucumber.java.en.Given;

public class SmokeSteps {
    @Given("a short legacy task")
    public void shortTask() throws Exception {
        Thread.sleep(150);
    }
}
