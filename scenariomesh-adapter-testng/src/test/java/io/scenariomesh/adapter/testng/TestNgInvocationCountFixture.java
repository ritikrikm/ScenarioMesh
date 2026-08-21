package io.scenariomesh.adapter.testng;

import org.testng.annotations.Test;

public final class TestNgInvocationCountFixture {
    @Test(invocationCount = 2)
    public void repeated() {
        // fixture only
    }
}
