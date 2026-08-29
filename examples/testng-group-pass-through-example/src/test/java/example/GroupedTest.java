package example;

import org.testng.annotations.Test;

public class GroupedTest {
    @Test(groups = "smoke")
    public void smokeRuns() {
        // ScenarioMesh should execute this method for -Dgroups=smoke.
    }

    @Test(groups = {"smoke", "slow"})
    public void excludedGroupMustWin() {
        throw new AssertionError("excludedGroups must veto an otherwise included TestNG method");
    }

    @Test(groups = "slow")
    public void slowMustStayFilteredOut() {
        throw new AssertionError("slow group must not execute when -Dgroups=smoke");
    }
}
