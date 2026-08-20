package example;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
        features = "classpath:features",
        glue = "example.steps",
        plugin = {"pretty", "json:target/cucumber-report/cucumber.json"})
public class RunCucumberTest {
}
