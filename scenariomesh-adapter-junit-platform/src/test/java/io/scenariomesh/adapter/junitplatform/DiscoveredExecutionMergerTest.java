package io.scenariomesh.adapter.junitplatform;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredExecutionMergerTest {
    private final DiscoveredExecutionMerger merger = new DiscoveredExecutionMerger();

    @Test
    void removesDirectDuplicateWhenSuiteOwnsSameCucumberExecution() {
        TestIdentifier direct = test("[engine:cucumber]/[feature:login]/[scenario:42]");
        TestIdentifier suite = test("[engine:junit-platform-suite]/[suite:RunCucumberTest]/[engine:cucumber]/[feature:login]/[scenario:42]");

        List<TestIdentifier> merged = merger.merge(List.of(direct, suite));

        assertEquals(1, merged.size());
        assertEquals(suite.getUniqueId(), merged.get(0).getUniqueId());
    }

    @Test
    void keepsDifferentCucumberExecutionsFromDifferentDiscoveryPaths() {
        TestIdentifier suite = test("[engine:junit-platform-suite]/[suite:RunCucumberTest]/[engine:cucumber]/[feature:login]/[scenario:42]");
        TestIdentifier direct = test("[engine:cucumber]/[feature:payment]/[scenario:18]");

        List<TestIdentifier> merged = merger.merge(List.of(suite, direct));

        assertEquals(2, merged.size());
    }

    @Test
    void keepsSameCucumberScenarioWhenTwoExplicitSuitesOwnIt() {
        TestIdentifier smoke = test("[engine:junit-platform-suite]/[suite:SmokeSuite]/[engine:cucumber]/[feature:login]/[scenario:42]");
        TestIdentifier regression = test("[engine:junit-platform-suite]/[suite:RegressionSuite]/[engine:cucumber]/[feature:login]/[scenario:42]");

        List<TestIdentifier> merged = merger.merge(List.of(smoke, regression));

        assertEquals(2, merged.size());
    }

    @Test
    void leavesOrdinaryJunitExecutionsAlone() {
        TestIdentifier first = test("[engine:junit-jupiter]/[class:LoginTest]/[method:first()]");
        TestIdentifier second = test("[engine:junit-jupiter]/[class:LoginTest]/[method:second()]");

        List<TestIdentifier> merged = merger.merge(List.of(first, second));

        assertEquals(2, merged.size());
    }

    @Test
    void cucumberIdentityUsesOnlyCucumberOwnedSuffix() {
        String direct = "[engine:cucumber]/[feature:login]/[scenario:42]";
        String suite = "[engine:junit-platform-suite]/[suite:Run]/" + direct;

        assertTrue(DiscoveredExecutionMerger.cucumberIdentity(direct).isPresent());
        assertEquals(
                DiscoveredExecutionMerger.cucumberIdentity(direct),
                DiscoveredExecutionMerger.cucumberIdentity(suite));
    }

    private TestIdentifier test(String uniqueId) {
        TestDescriptor descriptor = new AbstractTestDescriptor(UniqueId.parse(uniqueId), uniqueId) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        };
        return TestIdentifier.from(descriptor);
    }
}
