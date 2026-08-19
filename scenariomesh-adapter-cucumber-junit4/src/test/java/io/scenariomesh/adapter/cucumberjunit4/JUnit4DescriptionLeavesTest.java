package io.scenariomesh.adapter.cucumberjunit4;

import org.junit.jupiter.api.Test;
import org.junit.runner.Description;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JUnit4DescriptionLeavesTest {

    private final JUnit4DescriptionLeaves leaves = new JUnit4DescriptionLeaves();

    @Test
    void oneRunnerContainerCanProduceMultipleExecutableScenarioLeaves() {
        Description runner = Description.createSuiteDescription("GeneratedRunner123");
        Description feature = Description.createSuiteDescription("Opportunity feature");
        feature.addChild(Description.createTestDescription("GeneratedRunner123", "Create opportunity"));
        feature.addChild(Description.createTestDescription("GeneratedRunner123", "Update opportunity"));
        runner.addChild(feature);

        List<JUnit4DescriptionLeaves.Leaf> discovered = leaves.collect(runner);

        assertEquals(2, discovered.size());
        assertEquals(List.of(0, 0), discovered.get(0).selectorPath());
        assertEquals(List.of(0, 1), discovered.get(1).selectorPath());
        assertEquals("Opportunity feature > Create opportunity(GeneratedRunner123)", discovered.get(0).semanticKey());
        assertEquals("Opportunity feature > Update opportunity(GeneratedRunner123)", discovered.get(1).semanticKey());
    }

    @Test
    void runnerNameIsNotPartOfContainerIdentity() {
        Description firstRunner = runner("GeneratedRunnerA", "Same feature", "Same scenario");
        Description secondRunner = runner("GeneratedRunnerB", "Same feature", "Same scenario");

        String first = leaves.collect(firstRunner).get(0).semanticKey();
        String second = leaves.collect(secondRunner).get(0).semanticKey();

        assertEquals(first.replace("GeneratedRunnerA", "GeneratedRunnerB"), second);
    }

    private Description runner(String runnerName, String featureName, String scenarioName) {
        Description runner = Description.createSuiteDescription(runnerName);
        Description feature = Description.createSuiteDescription(featureName);
        feature.addChild(Description.createTestDescription(runnerName, scenarioName));
        runner.addChild(feature);
        return runner;
    }
}
