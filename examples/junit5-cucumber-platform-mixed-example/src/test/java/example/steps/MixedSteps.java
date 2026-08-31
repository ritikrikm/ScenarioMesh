package example.steps;

import example.Trace;
import io.cucumber.java.en.Given;

public class MixedSteps {
    @Given("a mixed framework scenario")
    public void mixedFrameworkScenario() throws Exception { Trace.record("cucumberScenarioRuns"); }
}
