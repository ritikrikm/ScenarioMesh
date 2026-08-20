package example;

import org.testng.annotations.Test;

public class GroupedTest {
    @Test(groups = "smoke")
    public void smokeRuns() {
        // Native Surefire/TestNG should execute this method for -Dgroups=smoke.
    }

    @Test(groups = "slow")
    public void slowMustStayFilteredOut() {
        throw new AssertionError("slow group must not execute when -Dgroups=smoke");
    }
}
