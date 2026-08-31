package io.scenariomesh.adapter.testng;

import org.testng.Assert;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

public final class TestNgParameterFixture {
    @Parameters("scenariomesh.test.parameter")
    @Test public void receivesParameter(String value) { Assert.assertEquals(value, "expected"); }
}
