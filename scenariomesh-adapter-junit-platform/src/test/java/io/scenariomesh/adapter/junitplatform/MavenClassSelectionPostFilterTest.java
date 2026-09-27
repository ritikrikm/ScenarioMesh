package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.DiscoverySelection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class MavenClassSelectionPostFilterTest {

    @Test
    void keepsNestedJupiterTestsOwnedByMavenSelectedOuterClass() {
        DiscoverySelection selection = new DiscoverySelection(
                List.of(".*NestedSelectionFixture\\.class"),
                List.of(".*\\$.*\\.class"));

        TestPlan plan = discover(selection);
        assertEquals(3, executableLeaves(plan));
    }

    @Test
    void stillHonorsMethodSelectionForNestedDescendants() {
        DiscoverySelection selection = new DiscoverySelection(
                List.of(".*NestedSelectionFixture\\.class"),
                List.of(".*\\$.*\\.class"),
                "NestedSelectionFixture#outer");

        TestPlan plan = discover(selection);
        assertEquals(1, executableLeaves(plan));
    }

    private TestPlan discover(DiscoverySelection selection) {
        return LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectClass(NestedSelectionFixture.class))
                        .filters(new MavenClassSelectionPostFilter(selection))
                        .build());
    }

    private long executableLeaves(TestPlan plan) {
        return plan.getRoots().stream()
                .flatMap(root -> plan.getDescendants(root).stream())
                .filter(TestIdentifier::isTest)
                .filter(identifier -> plan.getChildren(identifier).isEmpty())
                .count();
    }
}

class NestedSelectionFixture {

    @Test
    void outer() {}

    @Nested
    class Inner {

        @Test
        void first() {}

        @Test
        void second() {}
    }
}
